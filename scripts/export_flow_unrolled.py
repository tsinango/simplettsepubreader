#!/usr/bin/env python3
"""
Phase 3 — BV2 Flow Module Static Unroll Exporter

Extracts the `flow` submodule from a Bert-VITS2 2.0 checkpoint (G_0.pth) and
exports ONNX variants with the While loop statically unrolled to fixed
iteration counts (cnt=4, 8, 16).  This eliminates MNN's While0 control-flow
kernel overhead on OpenCL by making every WaveNet block a sequential
subgraph visible to the OpenCL backend's operator fusion.

Usage:
    python export_flow_unrolled.py \
        --ckpt base_model_22k/G_0.pth \
        --config base_model_22k/config.json \
        --output export_output \
        --cnt 4 8 16

Requires: torch, onnx, onnxoptimizer (optional).  Install:
    pip install torch onnx
"""

import os
import json
import argparse
from collections import OrderedDict

import torch
import torch.nn as nn
import torch.nn.functional as F


# ---- Minimal BV2 Flow module definition (covers G_0.pth's flow keys) --------

class ResidualBlock(nn.Module):
    """Single WN residual block: conv1×1 → dilated conv → gate → skip → residual."""
    def __init__(self, hidden_channels, kernel_size, dilation):
        super().__init__()
        self.hidden = hidden_channels
        self.dilation = dilation

        # MNN sees:  Conv1D → split → sigmoid/tanh → mul → conv1×1
        self.conv_pre = nn.Conv1d(hidden_channels, 2 * hidden_channels, 1)
        self.conv = nn.Conv1d(
            2 * hidden_channels, 2 * hidden_channels, kernel_size,
            padding=dilation * (kernel_size - 1) // 2,
            dilation=dilation, groups=hidden_channels,
        )
        self.conv_post = nn.Conv1d(hidden_channels, hidden_channels, 1)

    def forward(self, x, skip=None):
        h = self.conv_pre(x)
        h = self.conv(h)
        a, b = h.chunk(2, dim=1)
        h = torch.tanh(a) * torch.sigmoid(b)
        h = self.conv_post(h)
        if skip is None:
            return (x + h) / 2**0.5, h
        return (x + h) / 2**0.5, skip + h


class FlowWN(nn.Module):
    """WaveNet-style residual stack used in BV2's flow decoder.

    During training this runs as a `for _ in range(n_flows)` loop — that
    loop is what MNN compiles to While0.  In the unrolled export we replace
    the loop with `nn.ModuleList` of static length `cnt`.
    """
    def __init__(self, n_flows, hidden_channels, kernel_size, dilation_rate):
        super().__init__()
        self.blocks = nn.ModuleList()
        for i in range(n_flows):
            self.blocks.append(ResidualBlock(
                hidden_channels, kernel_size, dilation_rate ** (i % 4),
            ))

    def forward(self, x):
        skip = None
        for block in self.blocks:
            x, skip = block(x, skip)
        return x, skip


class Flow(nn.Module):
    def __init__(self, n_flows, hidden_channels=192, kernel_size=5, dilation_rate=1):
        super().__init__()
        self.conv_pre = nn.Conv1d(hidden_channels, hidden_channels, 1)
        self.conv_post = nn.Conv1d(hidden_channels, hidden_channels, 1)
        self.wn = FlowWN(n_flows, hidden_channels, kernel_size, dilation_rate)

    def forward(self, z_p, y_mask, g):
        z_p = z_p * y_mask
        h = self.conv_pre(z_p)
        h, skip = self.wn(h)
        h = h * y_mask
        h = self.conv_post(h)
        h = h * y_mask
        h = h + skip
        return h * y_mask


# ---- Load checkpoint and map keys ------------------------------------------

def load_flow_state_dict(ckpt_path: str, config: dict) -> OrderedDict:
    """Extract flow submodule's state dict from a BV2 G_0.pth checkpoint."""
    raw = torch.load(ckpt_path, map_location='cpu', weights_only=True)
    # G_0.pth stores a flat `state_dict` (or `model` key) containing all
    # submodules: `flow.wn.blocks.0.conv_pre.weight`, etc.
    sd = raw.get('model', raw.get('state_dict', raw))

    flow_sd = OrderedDict()
    for key, val in sd.items():
        if key.startswith('flow.'):
            flow_key = key[len('flow.'):]
            flow_sd[flow_key] = val

    if not flow_sd:
        # Maybe the checkpoint wraps differently; print keys to debug
        sample_keys = list(sd.keys())[:10]
        print(f"[WARN] No 'flow.*' keys found. Sample keys: {sample_keys}")
        return None

    print(f"[OK] Extracted {len(flow_sd)} flow state_dict keys")
    return flow_sd


def instantiate_flow(config: dict, cnt: int) -> nn.Module:
    """Create a flow module with cnt WN blocks (unrolled)."""
    hidden = config.get('gin_channels', 192)  # common default
    kernel = config.get('kernel_size', 5)
    dilation_rate = config.get('dilation_rate', 1)
    model = Flow(cnt, hidden_channels=hidden, kernel_size=kernel,
                 dilation_rate=dilation_rate)
    return model


# ---- Export one unrolled variant -------------------------------------------

def export_unrolled_onnx(flow_model: nn.Module, cnt: int, output_dir: str,
                         hidden: int = 192, seq_len: int = 128):
    """Export a cnt-unrolled flow model to ONNX with constant folding."""
    flow_model.eval()

    # Input shapes match what BV2_infer produces inside translate_run_flow:
    #   z_p : [1, hidden, seq_len]
    #   y_mask : [1, 1, seq_len]
    #   g : [1, hidden, 1]
    z_p = torch.randn(1, hidden, seq_len)
    y_mask = torch.ones(1, 1, seq_len)
    g = torch.randn(1, hidden, 1)

    onnx_path = os.path.join(output_dir, f"flow_unrolled_cnt{cnt}_len{seq_len}.onnx")
    torch.onnx.export(
        flow_model,
        (z_p, y_mask, g),
        onnx_path,
        input_names=["z_p", "y_mask", "g"],
        output_names=["z"],
        dynamic_axes={
            "z_p": {2: "T"},
            "y_mask": {2: "T"},
            "z": {2: "T"},
        },
        do_constant_folding=True,
        opset_version=16,
    )
    print(f"[OK] Exported {onnx_path}")
    return onnx_path


# ---- Main ------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Export static-unrolled BV2 flow ONNX variants")
    parser.add_argument("--ckpt", default="base_model_22k/G_0.pth",
                        help="Path to G_0.pth checkpoint")
    parser.add_argument("--config", default="base_model_22k/config.json",
                        help="Path to model config.json")
    parser.add_argument("--output", default="export_output",
                        help="Output directory for .onnx files")
    parser.add_argument("--cnt", nargs="+", type=int, default=[4, 8, 16],
                        help="Unroll iteration counts to export")
    parser.add_argument("--seq-len", type=int, default=128,
                        help="Static sequence length for export (dynamic after MNNConvert)")
    parser.add_argument("--mnn-convert", action="store_true",
                        help="Also run MNNConvert on produced .onnx files "
                             "(requires MNNConvert in PATH)")
    args = parser.parse_args()

    os.makedirs(args.output, exist_ok=True)

    with open(args.config, 'r') as f:
        config = json.load(f)
    hidden = config.get('gin_channels', 192)

    # Build + export each variant
    for cnt in args.cnt:
        model = instantiate_flow(config, cnt)
        sd = load_flow_state_dict(args.ckpt, config)
        if sd is None:
            # Print debug info and exit
            raw = torch.load(args.ckpt, map_location='cpu', weights_only=True)
            print(f"Checkpoint keys: {list(raw.keys())}")
            if 'model' in raw:
                subset = list(raw['model'].keys())[:20]
                print(f"model sub-keys: {subset}")
            return

        # Filter to match the instantiated module's parameter names
        # The checkpoint has `wn.blocks.X.conv_pre.weight` etc.
        # Our Flow.wn is a FlowWN which has `blocks = ModuleList(...)`
        compat_sd = OrderedDict()
        for k, v in sd.items():
            compat_sd[k] = v

        missing, unexpected = model.load_state_dict(compat_sd, strict=False)
        if missing:
            print(f"[WARN] cnt={cnt} missing keys: {missing[:5]}")
        if unexpected:
            print(f"[WARN] cnt={cnt} unexpected keys: {unexpected[:5]}")
        if not missing or not unexpected:
            print(f"[OK] cnt={cnt} state_dict loaded ({len(compat_sd)} keys)")

        # Export ONNX
        onnx_path = export_unrolled_onnx(model, cnt, args.output, hidden, args.seq_len)

        if args.mnn_convert:
            mnn_path = onnx_path.replace('.onnx', '.mnn')
            ret = os.system(f"MNNConvert -f ONNX --modelFile {onnx_path} "
                           f"--MNNModel {mnn_path} --fp16 --bizCode biz")
            if ret == 0:
                print(f"[OK] MNNConvert -> {mnn_path}")
            else:
                print(f"[ERR] MNNConvert failed (exit={ret})")

    print(f"\n[DONE] Exports in {args.output}/")
    print("Next: copy .mnn to model dir + register OPENCL_FLOW_UNROLLED=7 backend")


if __name__ == "__main__":
    main()
