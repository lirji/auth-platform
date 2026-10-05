package com.lrj.authz.governance.approval.port;

import com.lrj.authz.protocol.ApprovalDtos.*;

import java.util.Optional;

/** OA持久审批实例端口；业务检查路径不依赖此端口。 */
public interface ApprovalGateway {
    /** 超时后先按业务键查询；空值只允许远端明确404。 */
    Optional<Instance> find(Lookup lookup);

    /** 同业务键同快照幂等创建；改体必须拒绝。 */
    Instance start(Start command);
}
