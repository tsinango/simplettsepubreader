#include "backend/opencl/execution/image/GroupNormExecution.hpp"
#include "core/TensorUtils.hpp"

namespace MNN {
namespace OpenCL {

GroupNormExecution::GroupNormExecution(const std::vector<Tensor*>& inputs, const MNN::Op* op, Backend* backend)
    : CommonExecution(backend, op) {
    mUnits.resize(1);
    auto& unit = mUnits[0];
    mOpenCLBackend = static_cast<OpenCLBackend*>(backend);
    auto runtime = mOpenCLBackend->getOpenCLRuntime();

    auto group_norm_param = op->main_as_GroupNorm();
    mEpsilon = group_norm_param->epsilon();
    mGroup = group_norm_param->group();

    unit.kernel = runtime->buildKernel("groupnorm", "groupnorm", {}, mOpenCLBackend->getPrecision());
    OPENCL_CHECK_KERNEL_CTOR(unit.kernel);

    if (group_norm_param->gamma() && group_norm_param->beta()) {
        mHasGammaBeta = true;
        int size = group_norm_param->gamma()->size();
        auto bufferUnitSize = mOpenCLBackend->getPrecision() != BackendConfig::Precision_High
            ? sizeof(half_float::half) : sizeof(float);

        mGammaTensor.reset(Tensor::createDevice<float>({ALIGN_UP4(size)}));
        auto status = backend->onAcquireBuffer(mGammaTensor.get(), Backend::STATIC);
        if (!status) {
            MNN_ERROR("Out of memory when gamma is acquired in GroupNorm.\n");
            mValid = false;
            return;
        }
        mBetaTensor.reset(Tensor::createDevice<float>({ALIGN_UP4(size)}));
        status = backend->onAcquireBuffer(mBetaTensor.get(), Backend::STATIC);
        if (!status) {
            mValid = false;
            MNN_ERROR("Out of memory when beta is acquired in GroupNorm.\n");
            return;
        }

        {
            cl_int res;
            cl::Buffer& gammaBuffer = openCLBuffer(mGammaTensor.get());
            auto GammaPtrCL = mOpenCLBackend->getOpenCLRuntime()->commandQueue().enqueueMapBuffer(
                gammaBuffer, true, CL_MAP_WRITE, 0, ALIGN_UP4(size) * bufferUnitSize, nullptr, nullptr, &res);
            if (GammaPtrCL != nullptr && res == CL_SUCCESS) {
                if (mOpenCLBackend->getPrecision() != BackendConfig::Precision_High) {
                    for (int i = 0; i < size; i++)
                        ((half_float::half*)GammaPtrCL)[i] = (half_float::half)(group_norm_param->gamma()->data()[i]);
                    for (int i = size; i < ALIGN_UP4(size); i++)
                        ((half_float::half*)GammaPtrCL)[i] = (half_float::half)(0.0f);
                } else {
                    ::memset(GammaPtrCL, 0, ALIGN_UP4(size) * sizeof(float));
                    ::memcpy(GammaPtrCL, group_norm_param->gamma()->data(), size * sizeof(float));
                }
            }
            mOpenCLBackend->getOpenCLRuntime()->commandQueue().enqueueUnmapMemObject(gammaBuffer, GammaPtrCL);
        }
        {
            cl_int res;
            cl::Buffer& betaBuffer = openCLBuffer(mBetaTensor.get());
            auto BetaPtrCL = mOpenCLBackend->getOpenCLRuntime()->commandQueue().enqueueMapBuffer(
                betaBuffer, true, CL_MAP_WRITE, 0, ALIGN_UP4(size) * bufferUnitSize, nullptr, nullptr, &res);
            if (BetaPtrCL != nullptr && res == CL_SUCCESS) {
                if (mOpenCLBackend->getPrecision() != BackendConfig::Precision_High) {
                    for (int i = 0; i < size; i++)
                        ((half_float::half*)BetaPtrCL)[i] = (half_float::half)(group_norm_param->beta()->data()[i]);
                    for (int i = size; i < ALIGN_UP4(size); i++)
                        ((half_float::half*)BetaPtrCL)[i] = (half_float::half)(0.0f);
                } else {
                    ::memset(BetaPtrCL, 0, ALIGN_UP4(size) * sizeof(float));
                    ::memcpy(BetaPtrCL, group_norm_param->beta()->data(), size * sizeof(float));
                }
            }
            mOpenCLBackend->getOpenCLRuntime()->commandQueue().enqueueUnmapMemObject(betaBuffer, BetaPtrCL);
        }
    }
}

bool GroupNormExecution::onClone(Backend* bn, const Op* op, Execution** dst) {
    if (!mValid) return false;
    if (nullptr == dst) return true;
    auto gnExec = new GroupNormExecution({}, op, bn);
    gnExec->mHasGammaBeta = mHasGammaBeta;
    gnExec->mEpsilon = mEpsilon;
    gnExec->mGroup = mGroup;
    gnExec->mGammaTensor = mGammaTensor;
    gnExec->mBetaTensor = mBetaTensor;
    *dst = gnExec;
    return true;
}

ErrorCode GroupNormExecution::onEncode(const std::vector<Tensor*>& inputs, const std::vector<Tensor*>& outputs) {
    mUnits.resize(1);
    auto& unit = mUnits[0];
    auto runtime = mOpenCLBackend->getOpenCLRuntime();
    auto MaxLocalSize = std::min(runtime->getMaxWorkItemSizes()[0], (uint32_t)256);

    Tensor* input = inputs[0];
    Tensor* output = outputs[0];

    std::vector<int> inputShape = tensorShapeFormat(input);
    const int batch = inputShape[0];
    const int height = inputShape[1];
    const int width = inputShape[2];
    const int channels = inputShape[3];
    const int group_channels = channels / mGroup;

    std::set<std::string> buildOptions;
    if (mHasGammaBeta) {
        buildOptions.emplace("-DGAMMA_BETA");
    }

    unit.kernel = runtime->buildKernel("groupnorm", "groupnorm", buildOptions, mOpenCLBackend->getPrecision());

    int local_size = 1;
    while (local_size * 2 <= MaxLocalSize && local_size * 2 <= width) {
        local_size *= 2;
    }
    if (local_size < 1) local_size = 1;

    std::vector<uint32_t> mGWS = {
        static_cast<uint32_t>(UP_DIV(channels, 4)),
        static_cast<uint32_t>(height * width),
        static_cast<uint32_t>(batch)
    };
    std::vector<uint32_t> mLWS = { 1, 1, 1 };

    unit.globalWorkSize = {mGWS[0], mGWS[1], mGWS[2]};
    unit.localWorkSize = {mLWS[0], mLWS[1], mLWS[2]};

    uint32_t idx = 0;
    cl_int ret = CL_SUCCESS;
    ret |= unit.kernel->get().setArg(idx++, mGWS[0]);
    ret |= unit.kernel->get().setArg(idx++, mGWS[1]);
    ret |= unit.kernel->get().setArg(idx++, mGWS[2]);
    ret |= unit.kernel->get().setArg(idx++, openCLImage(input));
    ret |= unit.kernel->get().setArg(idx++, openCLImage(output));
    ret |= unit.kernel->get().setArg(idx++, static_cast<int32_t>(width));
    ret |= unit.kernel->get().setArg(idx++, static_cast<int32_t>(height));
    ret |= unit.kernel->get().setArg(idx++, static_cast<int32_t>(channels));
    ret |= unit.kernel->get().setArg(idx++, static_cast<int32_t>(mGroup));
    ret |= unit.kernel->get().setArg(idx++, static_cast<int32_t>(group_channels));
    ret |= unit.kernel->get().setArg(idx++, static_cast<int32_t>(batch));
    if (mHasGammaBeta) {
        ret |= unit.kernel->get().setArg(idx++, openCLBuffer(mGammaTensor.get()));
        ret |= unit.kernel->get().setArg(idx++, openCLBuffer(mBetaTensor.get()));
    }
    ret |= unit.kernel->get().setArg(idx++, mEpsilon);
    MNN_CHECK_CL_SUCCESS(ret, "setArg GroupNormExecution");

    mOpenCLBackend->recordKernel3d(unit.kernel, mGWS, mLWS);
    return NO_ERROR;
}

class GroupNormCreator : public OpenCLBackend::Creator {
public:
    virtual ~GroupNormCreator() = default;
    virtual Execution* onCreate(const std::vector<Tensor*>& inputs, const std::vector<Tensor*>& outputs,
                                const MNN::Op* op, Backend* backend) const override {
        for (int i = 0; i < inputs.size(); ++i) {
            TensorUtils::setTensorSupportPack(inputs[i], false);
        }
        for (int i = 0; i < outputs.size(); ++i) {
            TensorUtils::setTensorSupportPack(outputs[i], false);
        }
        OPENCL_CREATOR_CHECK(new GroupNormExecution(inputs, op, backend));
    }
};

REGISTER_OPENCL_OP_CREATOR(GroupNormCreator, OpType_GroupNorm, IMAGE);

} // namespace OpenCL
} // namespace MNN
