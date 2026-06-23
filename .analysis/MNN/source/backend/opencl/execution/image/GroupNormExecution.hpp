#ifndef GroupNormExecution_hpp
#define GroupNormExecution_hpp

#include "CommonExecution.hpp"

namespace MNN {
namespace OpenCL {

class GroupNormExecution : public CommonExecution {
public:
    GroupNormExecution(const std::vector<Tensor*>& inputs, const MNN::Op* op, Backend* backend);
    virtual ~GroupNormExecution() = default;
    virtual ErrorCode onEncode(const std::vector<Tensor*>& inputs,
                               const std::vector<Tensor*>& outputs) override;
    bool onClone(Backend* bn, const Op* op, Execution** dst) override;
private:
    OpenCLBackend* mOpenCLBackend = nullptr;
    float mEpsilon;
    int mGroup;
    bool mHasGammaBeta = false;
    bool mValid = true;
    std::shared_ptr<Tensor> mGammaTensor;
    std::shared_ptr<Tensor> mBetaTensor;
};

} // namespace OpenCL
} // namespace MNN
#endif /* GroupNormExecution_hpp */
