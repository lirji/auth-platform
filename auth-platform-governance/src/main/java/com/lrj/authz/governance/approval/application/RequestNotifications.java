package com.lrj.authz.governance.approval.application;

import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.approval.persistence.RequestMapper;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** 独立站内投递任务；业务提交与通知故障隔离，领取和回执均持久化。 */
public final class RequestNotifications {
    private final RequestMapper mapper;
    private final TransactionTemplate tx;

    /** 复用同库持久任务，不引入未经选型的邮件或消息产品。 */
    public RequestNotifications(RequestMapper mapper, TransactionTemplate tx) {
        this.mapper = mapper;
        this.tx = tx;
    }

    /** 单轮只处理一条，可由外部调度有界循环；重复投递按稳定ID去重。 */
    public boolean step(Partition p) {
        AccessValues.partition(p);
        String lease = UUID.randomUUID().toString();
        String id =
                tx.execute(
                        status -> {
                            mapper.exhaustNotices(p);
                            return mapper.claimNotice(p, lease);
                        });
        if (id == null) return false;
        try {
            tx.executeWithoutResult(
                    status -> {
                        mapper.insertNotice(id, lease);
                        if (mapper.finishNotice(id, lease) != 1)
                            throw new GovernanceException(
                                    GovernanceException.Code.VERSION_CONFLICT);
                    });
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(status -> mapper.failNotice(id, lease));
        }
        return true;
    }
}
