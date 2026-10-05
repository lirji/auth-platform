package com.lrj.authz.governance.application;

import static com.lrj.authz.governance.application.GovernanceException.Code.AUTHZ_STATE_NOT_READY;

import com.lrj.authz.governance.domain.FenceModels.*;
import com.lrj.authz.governance.persistence.FenceMapper;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** A/C各自使用主库新快照；远程图调用必须发生在两个快照事务之间。 */
public final class ReadFence {
    private final FenceMapper mapper;
    private final TransactionTemplate fresh;

    /** 显式REQUIRES_NEW隔离外层旧事务，禁止通过同一可重复读快照掩盖撤权。 */
    public ReadFence(FenceMapper mapper, PlatformTransactionManager transactions) {
        this.mapper = mapper;
        fresh = new TransactionTemplate(transactions);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        fresh.setReadOnly(true);
        fresh.setTimeout(5);
    }

    /** 栅栏与候选事实在同一个短事务读取；过时身份不会被静默升级成新成员代际。 */
    public <T> Snapshot<T> snapshot(AccessContext context, Supplier<T> read) {
        return fresh.execute(
                status -> {
                    Stamp stamp = mapper.stamp(context);
                    if (stamp == null
                            || stamp.membershipVersion() != context.membershipVersion()
                            || stamp.principalVersion() != context.principalVersion())
                        throw stale();
                    return new Snapshot<>(stamp, read.get());
                });
    }

    /** C已取得新快照后逐字段比较，不比较Opaque水位的大小。 */
    public void requireSame(Stamp before, Stamp after) {
        if (before == null || !before.equals(after)) throw stale();
    }

    private static GovernanceException stale() {
        return new GovernanceException(AUTHZ_STATE_NOT_READY);
    }
}
