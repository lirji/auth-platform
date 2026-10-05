package com.lrj.authz.governance.access.application;

import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.access.persistence.AccessMapper;
import com.lrj.authz.governance.approval.application.ApprovalInbox;
import com.lrj.authz.governance.approval.persistence.ApprovalInboxMapper;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.governance.shared.web.AccessWeb;
import com.lrj.authz.protocol.ApprovalDtos.*;

import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 消费固定Inbox事件并在同一事务提交申请、Grant、范围、投影意图及审计。 */
public final class ApprovalDecisionConsumer {
    private final AccessRequests requests;
    private final AccessMapper access;
    private final ApprovalInboxMapper inbox;
    private final TransactionTemplate tx;

    /** 与申请/取消保持分区锁在前、事件锁在后的顺序，避免并发反向加锁。 */
    public ApprovalDecisionConsumer(
            AccessRequests requests,
            AccessMapper access,
            ApprovalInboxMapper inbox,
            TransactionTemplate tx) {
        this.requests = requests;
        this.access = access;
        this.inbox = inbox;
        this.tx = tx;
    }

    /** 单轮至多20个事件，不在事务中调用OA或图。 */
    public int step(Partition p) {
        AccessValues.partition(p);
        int count = 0;
        for (String event : inbox.ready(p)) {
            Boolean applied =
                    tx.execute(
                            status -> {
                                // 即便准入已停用仍锁行，使事件安全拒绝而非永久积压。
                                if (access.lockPartition(p) == null) return false;
                                var row = inbox.lock(ApprovalInbox.PRODUCER, event);
                                if (row == null || !Status.RECEIVED.code().equals(row.status()))
                                    return false;
                                var decision =
                                        AccessWeb.read(
                                                new ByteArrayInputStream(
                                                        row.payloadJson()
                                                                .getBytes(StandardCharsets.UTF_8)),
                                                Decision.class);
                                ApprovalInbox.validate(decision, p);
                                var receipt = requests.decide(p, decision);
                                if (inbox.finish(
                                                ApprovalInbox.PRODUCER,
                                                event,
                                                receipt.status(),
                                                receipt.result())
                                        != 1)
                                    throw new GovernanceException(
                                            GovernanceException.Code.VERSION_CONFLICT);
                                return true;
                            });
            if (Boolean.TRUE.equals(applied)) count++;
        }
        return count;
    }
}
