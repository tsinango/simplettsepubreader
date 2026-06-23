#ifdef MNN_SUPPORT_FP16
#pragma OPENCL EXTENSION cl_khr_fp16 : enable
#endif

__constant sampler_t SAMPLER = CLK_NORMALIZED_COORDS_FALSE | CLK_ADDRESS_CLAMP | CLK_FILTER_NEAREST;

__kernel void groupnorm(__private int global_dim0, __private int global_dim1, __private int global_dim2,
                        __read_only image2d_t input,
                        __write_only image2d_t output,
                        __private const int width,
                        __private const int height,
                        __private const int channel,
                        __private const int groups,
                        __private const int group_channels,
                        __private const int batch,
#ifdef GAMMA_BETA
                        __global const FLOAT *gamma,
                        __global const FLOAT *beta,
#endif
                        __private float epsilon) {
    int3 pos = (int3)(get_global_id(0), get_global_id(1), get_global_id(2));
    if (pos.x < global_dim0 && pos.y < global_dim1 && pos.z < global_dim2) {
        const int b = pos.z;
        const int ch = pos.x;
        const int h = pos.y / height;
        const int w = pos.y % height;

        int group_id = ch / group_channels;
        float sum = 0.0f;
        float sum_sq = 0.0f;
        int count = 0;

        for (int c = group_id * group_channels; c < (group_id + 1) * group_channels; ++c) {
            int c4 = c / 4;
            int c4_off = c % 4;
            int img_x = c4 * width + w;
            int img_y = b * height + h;
            float4 vals = convert_float4(RI_F(input, SAMPLER, (int2)(img_x, img_y)));
            float val;
            if (c4_off == 0) val = vals.x;
            else if (c4_off == 1) val = vals.y;
            else if (c4_off == 2) val = vals.z;
            else val = vals.w;
            sum += val;
            sum_sq += val * val;
            count++;
        }

        float mean = sum / (float)count;
        float var = sum_sq / (float)count - mean * mean;
        float inv_std = rsqrt(var + epsilon);

        // Write normalized output
        int c4 = ch;
        int c4_off = 0;
        int img_x_out = c4 * width + w;
        int img_y_out = b * height + h;
        float4 in_vals = convert_float4(RI_F(input, SAMPLER, (int2)(img_x_out, img_y_out)));

        float4 out_vals;
#ifdef GAMMA_BETA
        out_vals.x = (in_vals.x - mean) * inv_std * gamma[ch * 4 + 0] + beta[ch * 4 + 0];
        out_vals.y = (in_vals.y - mean) * inv_std * gamma[ch * 4 + 1] + beta[ch * 4 + 1];
        out_vals.z = (in_vals.z - mean) * inv_std * gamma[ch * 4 + 2] + beta[ch * 4 + 2];
        out_vals.w = (in_vals.w - mean) * inv_std * gamma[ch * 4 + 3] + beta[ch * 4 + 3];
#else
        out_vals.x = (in_vals.x - mean) * inv_std;
        out_vals.y = (in_vals.y - mean) * inv_std;
        out_vals.z = (in_vals.z - mean) * inv_std;
        out_vals.w = (in_vals.w - mean) * inv_std;
#endif
        WI_F(output, (int2)(img_x_out, img_y_out), CONVERT_FLOAT4(out_vals));
    }
}
