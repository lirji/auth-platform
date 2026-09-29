package com.lrj.authz.governance.application;

import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.ApprovalInboxMapper;
import com.lrj.authz.governance.web.AccessWeb;
import com.lrj.authz.protocol.ApprovalDtos.*;
import com.lrj.authz.protocol.ApprovalSignature;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 可信回调先持久接收，消费时再原子推进申请与Grant；同ID改体不能覆盖原消息。 */
public final class ApprovalInbox {
    public static final String PATH="/internal/oa-approval/v1/events";
    public static final String PRODUCER="oa-platform";
    private final ApprovalInboxMapper mapper;
    private final TransactionTemplate tx;
    private final com.lrj.authz.governance.persistence.RequestMapper requests;
    /** 复用治理专用事务；只由绑定分区的受控传输入口调用。 */
    public ApprovalInbox(ApprovalInboxMapper mapper,com.lrj.authz.governance.persistence.RequestMapper requests,TransactionTemplate tx) { this.mapper=mapper;this.requests=requests;this.tx=tx; }

    /** 原文签名验证和事件业务去重分别保护重放；冲突作为返回值，保证证据提交。 */
    public Receipt receive(Partition configured,String key,String signature,byte[] body) {
        AccessValues.partition(configured);
        String nonce;
        try { nonce=ApprovalSignature.verify(key,PRODUCER,configured.environment(),PATH,body,signature,Instant.now()); }
        catch(IllegalArgumentException invalid) { throw new GovernanceException(INVALID_CREDENTIAL); }
        Decision event=AccessWeb.read(new ByteArrayInputStream(body),Decision.class);
        validate(event,configured);
        String hash=rawHash(body);
        return tx.execute(status -> {
            if(mapper.nonce(PRODUCER,nonce)!=1) throw new GovernanceException(COMMAND_CONFLICT);
            mapper.insert(configured,PRODUCER,event.eventId(),event.requestId(),hash,new String(body,StandardCharsets.UTF_8));
            var old=mapper.lock(PRODUCER,event.eventId());
            if(old==null) throw new GovernanceException(VERSION_CONFLICT);
            if(!hash.equals(old.payloadHash())) {
                mapper.conflict(PRODUCER,event.eventId(),hash,old.payloadHash());
                return new Receipt(event.eventId(),Status.CONFLICT.code(),"EVENT_PAYLOAD_CONFLICT");
            }
            if(Status.RECEIVED.code().equals(old.status())) {
                var request=requests.request(configured,event.requestId());
                var policy=request==null?null:requests.policy(configured,request.policyId());
                if(request==null || policy==null || event.requestVersion()!=request.requestVersion() || event.aggregateVersion()!=1
                        || !event.snapshotHash().equals(request.snapshotHash()) || !event.policyId().equals(policy.id())
                        || event.policyVersion()!=policy.policyVersion() || !event.approverMembershipId().equals(policy.approverMembershipId())
                        || event.approverGeneration()!=policy.approverGeneration()
                        || event.approverMembershipId().equals(request.membershipId())
                        || request.approvalInstanceId()!=null && !request.approvalInstanceId().equals(event.approvalInstanceId())) {
                    if(mapper.finish(PRODUCER,event.eventId(),Status.REJECTED.code(),"REQUEST_BINDING_MISMATCH")!=1) throw new GovernanceException(VERSION_CONFLICT);
                    return new Receipt(event.eventId(),Status.REJECTED.code(),"REQUEST_BINDING_MISMATCH");
                }
            }
            return new Receipt(event.eventId(),old.status(),old.result());
        });
    }

    /** 旧schema或新未知事件不能猜测批准；旧聚合版本保留给消费状态机判定。 */
    public static void validate(Decision e,Partition p) {
        if(e==null || !p.tenantId().equals(e.tenantId()) || !p.applicationId().equals(e.applicationId()) || !p.environment().equals(e.environment())) throw new GovernanceException(ACCESS_DENIED);
        BootstrapCommand.uuid(e.eventId());BootstrapCommand.uuid(e.requestId());BootstrapCommand.uuid(e.policyId());
        BootstrapCommand.uuid(e.approverMembershipId());BootstrapCommand.uuid(e.correlationId());BootstrapCommand.bounded(e.approvalInstanceId(),100);
        if(!PRODUCER.equals(e.producer()) || !com.lrj.authz.protocol.ApprovalDtos.DECIDED_EVENT.equals(e.eventType()) || e.schemaVersion()!=1
                || e.requestVersion()<1 || e.aggregateVersion()<1 || e.policyVersion()<1 || e.approverGeneration()<1
                || e.snapshotHash()==null || !e.snapshotHash().matches("[a-f0-9]{64}")
                || e.outcome()==null || !Set.of(Outcome.APPROVED.code(),Outcome.REJECTED.code()).contains(e.outcome())) throw new GovernanceException(INVALID_ARGUMENT);
        if(AccessWeb.instant(e.decisionTime()).isAfter(Instant.now().plusSeconds(120))) throw new GovernanceException(INVALID_ARGUMENT);
    }

    private static String rawHash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA256不可用",failure); }
    }
}
