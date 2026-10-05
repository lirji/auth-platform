package com.lrj.authz.governance.access.application;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import com.lrj.authz.governance.access.persistence.CapabilityLifecycleMapper;
import com.lrj.authz.governance.shared.application.GovernanceException;

import java.util.List;

/** 仅新增使用调用此门禁；已有访问、回收和原成功命令不能被当作新增授权。 */
final class CapabilityUsage {
    private CapabilityUsage() {}

    /** 必须在写事务内取得共享应用锁，避免检查之后Owner已弃用仍提交新来源。 */
    static void requireCreatable(
            CapabilityLifecycleMapper mapper, String application, List<String> capabilities) {
        if (mapper.lockUsage(application) == null) throw new GovernanceException(ACCESS_DENIED);
        if (mapper.deprecated(application, capabilities))
            throw new GovernanceException(CAPABILITY_DEPRECATED);
    }
}
