#include "opencl_source_map.hpp" 
namespace MNN { 
const char* attention_buf = 
"#ifdef MNN_SUPPORT_FP16\n"
"#pragma OPENCL EXTENSION cl_khr_fp16 : enable\n"
"#endif\n"
"__kernel void attention(__global const FLOAT *in, __global const FLOAT *weight, __global FLOAT *out,\n"
" __private const int N, __private const int C) {\n"
" int gid = get_global_id(0);\n"
" if (gid < N) {\n"
" float sum = 0;\n"
" for (int c = 0; c < C; c++) sum += in[gid * C + c] * weight[c];\n"
" out[gid] = sum;\n"
" }\n"
"}\n"
;
} // namespace MNN
