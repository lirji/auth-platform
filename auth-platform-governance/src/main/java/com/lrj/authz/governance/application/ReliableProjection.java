package com.lrj.authz.governance.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.FenceModels.*;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.protocol.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.lrj.authz.protocol.ProjectionGraph.Failure.Code.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 有界可靠执行器：SQL租约只管领取，图CAS阻止失租旧进程写回。 */
public final class ReliableProjection {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ReliableProjectionMapper mapper;
    private final FenceMapper fences;
    private final ProjectionGraph graph;
    private final TransactionTemplate tx;

    /** 网络调用不使用外层业务事务；独立5秒事务只包领取、规划和确认。 */
    public ReliableProjection(ReliableProjectionMapper mapper, FenceMapper fences,
                              PlatformTransactionManager transactions, ProjectionGraph graph) {
        this.mapper = mapper; this.fences = fences; this.graph = graph;
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(5);
    }

    /** 每次最多一个50项批次；宿主决定下一次调度，禁止无界内部重试。 */
    public Step step(Partition partition, Kind kind, String worker) {
        AccessValues.partition(partition); BootstrapCommand.uuid(worker);
        if (kind == null || TransactionSynchronizationManager.isActualTransactionActive()) throw new GovernanceException(INVALID_ARGUMENT);
        Fence policy = fences.policy(partition);
        if (policy == null) throw new GovernanceException(ACCESS_DENIED);
        Fence initial = kind == Kind.POLICY ? policy : fences.directory(partition.tenantId());
        if (initial.state() == State.BLOCKED) return Step.BLOCKED;
        Target target = new Target(kind, initial.id(), partition);
        Stream lease = tx.execute(status -> {
            mapper.register(target);
            return mapper.claim(target.fenceId(), worker);
        });
        if (lease == null) return Step.BUSY;
        try {
            // 先核对远端事实：超时可能已经提交，不能直接把旧payload套上新marker重试。
            var marker = graph.readMarker(target.fenceId());
            Work work = tx.execute(status -> prepare(target, lease, marker));
            if (work.operation() == null) return work.result();
            Operation op = work.operation(); Payload payload = payload(op);
            String token = graph.compareAndWrite(target.fenceId(), op.expectedMarker(), op.id(), payload.relationships());
            return tx.execute(status -> {
                confirm(target, lease, op, token);
                return mapper.fence(target).state() == State.READY ? Step.READY : Step.APPLIED;
            });
        } catch (RuntimeException failure) {
            return tx.execute(status -> {
                Stream current = mapper.owned(lease);
                if (current == null) return Step.BUSY;
                boolean quarantine = failure instanceof ProjectionGraph.Failure f
                        && (f.code() == MARKER_CONFLICT || f.code() == PROTOCOL_INVALID);
                mapper.attempted(lease.fenceId()); mapper.failed(lease);
                if (quarantine || current.failures() >= 4) { mapper.block(target); return Step.BLOCKED; }
                return Step.RETRY_WAIT;
            });
        } finally {
            tx.executeWithoutResult(status -> mapper.release(lease));
        }
    }

    private Work prepare(Target target, Stream lease, Optional<ProjectionGraph.Marker> remote) {
        Stream current = requireOwned(lease);
        Fence fence = mapper.fence(target);
        if (fence.state() == State.BLOCKED) return new Work(Step.BLOCKED, null);
        String observed = remote.map(ProjectionGraph.Marker::operationId).orElse(null);
        if (!Objects.equals(observed, current.marker())) {
            Operation committed = observed == null ? null : mapper.operation(target.fenceId(), observed);
            if (committed == null || committed.batchNo() <= current.confirmedBatch()
                    || !Objects.equals(committed.expectedMarker(), current.marker())) throw new ProjectionGraph.Failure(MARKER_CONFLICT);
            payload(committed);
            Operation pending = mapper.pending(target.fenceId());
            if (pending != null && !pending.id().equals(committed.id())) one(mapper.supersede(pending.id()));
            confirm(target, lease, committed, remote.orElseThrow().readToken());
            return new Work(Step.RECOVERED, null);
        }
        if (fence.state() == State.READY && fence.desiredEpoch() == fence.appliedEpoch()) {
            // 已完全一致核对远端marker和当前租约；成功轮次应结束连续失败预算，避免空闲分区积累零星超时后误隔离。
            if (current.failures() > 0) one(mapper.successfulRead(lease));
            return new Work(Step.READY, null);
        }
        Operation pending = mapper.pending(target.fenceId());
        if (pending != null && (pending.targetEpoch() != fence.desiredEpoch() || !Objects.equals(pending.expectedMarker(), current.marker()))) {
            one(mapper.supersede(pending.id())); pending = null;
        }
        if (pending != null) { payload(pending); return new Work(null, pending); }
        String after = current.targetEpoch() == fence.desiredEpoch() ? current.scanCursor() : "";
        List<GrantChange> page = target.kind() == Kind.POLICY ? mapper.grants(target, after) : List.of();
        List<GroupChange> groupPage = target.kind() == Kind.DIRECTORY ? mapper.groups(target, after) : List.of();
        boolean last = page.size() <= 50 && groupPage.size() <= 50;
        List<GrantChange> changes = List.copyOf(page.subList(0, Math.min(page.size(), 50)));
        List<GroupChange> groups = List.copyOf(groupPage.subList(0, Math.min(groupPage.size(), 50)));
        List<RelationshipUpdate> relationships = new ArrayList<>();
        for (GrantChange g : changes) {
            var resource = ResourceRef.of(ProjectionGraph.GRANT_TYPE, g.id());
            var subject = g.groupId()==null?SubjectRef.of(ProjectionGraph.MEMBER_TYPE, g.membershipId() + "_g" + g.generation())
                    :SubjectRef.ofRelation(ProjectionGraph.GROUP_TYPE,g.groupId(),"member");
            relationships.add(g.present()?RelationshipUpdate.touch(resource,"assignee",subject):RelationshipUpdate.delete(resource,"assignee",subject));
        }
        for (GroupChange g : groups) {
            var resource=ResourceRef.of(ProjectionGraph.GROUP_TYPE,g.groupId());
            var subject=SubjectRef.of(ProjectionGraph.MEMBER_TYPE,g.membershipId()+"_g"+g.generation());
            relationships.add(g.present()?RelationshipUpdate.touch(resource,"member",subject):RelationshipUpdate.delete(resource,"member",subject));
        }
        String cursor = !changes.isEmpty()?changes.getLast().id():!groups.isEmpty()?groups.getLast().id():after;
        String json = encode(new Payload(changes, relationships));
        long batch = current.batchCounter() + 1;
        String hash = hash(target.fenceId(), fence.desiredEpoch(), batch, current.marker(), json, cursor, last);
        Operation planned = new Operation(UUID.randomUUID().toString(), target.fenceId(), fence.desiredEpoch(), batch,
                current.marker(), json, hash, cursor, last, OperationState.PENDING, 0, lease.workerId(), lease.leaseGeneration());
        one(mapper.advanceBatch(target.fenceId(), current.batchCounter())); one(mapper.insert(planned));
        return new Work(null, planned);
    }

    private void confirm(Target target, Stream lease, Operation operation, String token) {
        Stream current = requireOwned(lease);
        if (token == null || token.isBlank() || token.length() > 2048) throw new ProjectionGraph.Failure(PROTOCOL_INVALID);
        if (operation.batchNo() <= current.confirmedBatch() || !Objects.equals(operation.expectedMarker(), current.marker()))
            throw new ProjectionGraph.Failure(MARKER_CONFLICT);
        Payload payload = payload(operation);
        one(mapper.receipt(operation, token, lease.workerId()));
        for (GrantChange grant : payload.grants()) {
            if (grant.present()) mapper.activate(grant, token);
            mapper.completeLegacy(grant);
            mapper.grantReceipt(grant,operation.id());
        }
        one(mapper.applied(operation.id())); one(mapper.confirmStream(operation, token));
        // 条件更新会在新desired出现时返回0，继续UPDATING是正常并发结果。
        if (operation.lastBatch()) mapper.ready(target, operation, token);
    }

    private Stream requireOwned(Stream lease) {
        Stream current = mapper.owned(lease);
        if (current == null) throw new GovernanceException(AUTHZ_STATE_NOT_READY);
        return current;
    }
    private Payload payload(Operation operation) {
        if (!operation.contentHash().equals(hash(operation.fenceId(), operation.targetEpoch(), operation.batchNo(),
                operation.expectedMarker(), operation.payloadJson(), operation.afterCursor(), operation.lastBatch())))
            throw new ProjectionGraph.Failure(MARKER_CONFLICT);
        try {
            Payload payload = JSON.readValue(operation.payloadJson(), Payload.class);
            if (payload.grants().size() > 50 || payload.relationships().size() > 100) throw new ProjectionGraph.Failure(PROTOCOL_INVALID);
            return payload;
        } catch (java.io.IOException failure) { throw new ProjectionGraph.Failure(PROTOCOL_INVALID); }
    }
    private String encode(Payload payload) {
        try { return JSON.writeValueAsString(payload); }
        catch (java.io.IOException failure) { throw new ProjectionGraph.Failure(PROTOCOL_INVALID); }
    }
    private String hash(String fence, long epoch, long batch, String expected, String json, String after, boolean last) {
        return AccessValues.hash(fence, epoch, batch, expected, json, after, last);
    }
    private static void one(int count) { if (count != 1) throw new GovernanceException(VERSION_CONFLICT); }
    private record Work(Step result, Operation operation) {}
}
