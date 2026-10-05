package com.lrj.authz.governance.approval.application;

import com.lrj.authz.governance.access.domain.AccessModels.Partition;
import com.lrj.authz.governance.approval.domain.RequestModels.*;
import com.lrj.authz.governance.approval.persistence.RequestMapper;
import com.lrj.authz.governance.approval.port.ApprovalGateway;
import com.lrj.authz.governance.authorization.application.ScopeRules;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.shared.application.AccessValues;
import com.lrj.authz.governance.shared.application.GovernanceException;
import com.lrj.authz.protocol.ApprovalDtos.*;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** 一次只投递一个持久启动意图；远程调用在短SQL事务之外，崩溃由租约恢复。 */
public final class ApprovalStartDelivery {
    private final RequestMapper mapper;
    private final TransactionTemplate tx;
    private final ApprovalGateway gateway;

    /** 明确依赖端口与同库事务，调用方自行设置有界批次预算。 */
    public ApprovalStartDelivery(
            RequestMapper mapper, TransactionTemplate tx, ApprovalGateway gateway) {
        this.mapper = mapper;
        this.tx = tx;
        this.gateway = gateway;
    }

    /** 返回本次是否领取任务；失败保留可查询技术状态，绝不重建申请。 */
    public boolean step(Partition p) {
        AccessValues.partition(p);
        String lease = UUID.randomUUID().toString();
        String id =
                tx.execute(
                        status -> {
                            mapper.exhaustStarts(p);
                            return mapper.claimStart(p, lease);
                        });
        if (id == null) return false;
        Request request = mapper.request(p, id);
        Policy policy = mapper.policy(p, request.policyId());
        try {
            var lookup =
                    new Lookup(
                            p.tenantId(),
                            p.applicationId(),
                            p.environment(),
                            id,
                            request.requestVersion(),
                            request.snapshotHash());
            var instance =
                    gateway.find(lookup)
                            .orElseGet(
                                    () ->
                                            gateway.start(
                                                    new Start(
                                                            p.tenantId(),
                                                            p.applicationId(),
                                                            p.environment(),
                                                            id,
                                                            request.requestVersion(),
                                                            request.snapshotHash(),
                                                            policy.id(),
                                                            policy.policyVersion(),
                                                            request.policyHash(),
                                                            request.membershipId(),
                                                            request.generation(),
                                                            policy.approverMembershipId(),
                                                            policy.approverGeneration(),
                                                            request.roleId(),
                                                            AccessValues.read(
                                                                    request.capabilitiesJson()),
                                                            ScopeRules.decode(request.scopeJson()),
                                                            request.validFrom().toString(),
                                                            request.validTo().toString(),
                                                            request.reason())));
            if (instance == null
                    || !id.equals(instance.requestId())
                    || instance.requestVersion() != request.requestVersion()
                    || !request.snapshotHash().equals(instance.snapshotHash()))
                throw new GovernanceException(GovernanceException.Code.BINDING_CONFLICT);
            BootstrapCommand.bounded(instance.approvalInstanceId(), 100);
            tx.executeWithoutResult(
                    status -> {
                        // 先CAS领取回执；旧执行器失去租约后不能绑定或覆盖另一次投递结果。
                        if (mapper.finishStart(id, lease) != 1) return;
                        if (mapper.bindInstance(p, id, instance.approvalInstanceId()) != 1
                                || mapper.auditStart(id, UUID.randomUUID().toString(), lease) != 1)
                            throw new GovernanceException(
                                    GovernanceException.Code.VERSION_CONFLICT);
                    });
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(status -> mapper.failStart(id, lease, "OA_START_UNCONFIRMED"));
        }
        return true;
    }
}
