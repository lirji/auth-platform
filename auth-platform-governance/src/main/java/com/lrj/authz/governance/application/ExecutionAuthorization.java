package com.lrj.authz.governance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.persistence.ExecutionMapper;
import com.lrj.authz.protocol.*;
import com.lrj.authz.protocol.ExecutionAccessDtos.*;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeDtos.Alternative;
import com.lrj.authz.protocol.ScopeAccessDtos.ResourceDecision;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 将经用户认证的授权路径绑定到后台任务；引用不产生独立于Grant的权利。 */
public final class ExecutionAuthorization {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SYNCHRONOUS_INVENTORY = Set.of("inventory.read", "inventory.receive");
    private static final Map<String,String> DIRECTORY = Map.of(
            "merchant.read", ScopeDtos.MERCHANT_RESOURCE_TYPE, "merchant.create", ScopeDtos.MERCHANT_RESOURCE_TYPE,
            "store.directory.read", ScopeDtos.STORE_RESOURCE_TYPE, "store.create", ScopeDtos.STORE_RESOURCE_TYPE);
    private static final Set<String> MEMBER_CAPABILITIES = Set.of("member.read", "member.create", "member.profile.update", "member.status.update",
            "growth.read", "growth.adjust", "growth.recalculate", "member_tag.read", "member_tag.define", "member_tag.assign", "member_behavior.read", "member_behavior.update", "member_behavior.rebuild",
            "member_cycle.read", "member_cycle.evaluate", "cycle_benefit.grant", "points.read", "points.adjust", "points.expire");
    private static final Set<String> MEMBER_POLICY_CAPABILITIES = Set.of("growth.policy.read", "growth.policy.publish",
            "member_cycle.policy.read", "member_cycle.policy.publish", "cycle_benefit.read", "cycle_benefit.define",
            "points.policy.read", "points.policy.publish");
    private static final Set<String> POINT_OFFER_CAPABILITIES = Set.of("point_offer.read", "point_offer.define", "point_offer.status.update");
    private static final Set<String> COUPON_DEFINITION_CAPABILITIES = Set.of("coupon_definition.read", "coupon_definition.create");
    private static final Set<String> COUPON_DELIVERY_CAPABILITIES = Set.of("coupon_delivery.create", "coupon_delivery.read", "coupon_delivery.control", "coupon_delivery.pump");
    private static final Set<String> COUPON_DELIVERY_COLLECTION_ONLY = Set.of("coupon_delivery.create", "coupon_delivery.pump");
    private static final Set<String> COUPON_DELIVERY_DURABLE_CAPABILITIES = Set.of("coupon_delivery.create", "coupon_delivery.control");
    private static final Set<String> ENTITLEMENT_DEFINITION_CAPABILITIES = Set.of("entitlement_definition.read", "entitlement_definition.create");
    private static final Set<String> CAMPAIGN_CAPABILITIES = Set.of("campaign.read", "campaign.create", "campaign.preview", "campaign.submit",
            "campaign.approve", "campaign.reject", "campaign.publish", "campaign.pause", "budget.read");
    private static final Set<String> CAMPAIGN_COLLECTION_ONLY = Set.of("campaign.read", "campaign.create", "budget.read");
    private static final Set<String> AUDIENCE_CAPABILITIES = Set.of("audience.read", "audience.create");
    private static final Set<String> SEGMENT_CAPABILITIES = Set.of("segment.read", "segment.create", "segment.schedule", "segment.refresh", "segment.control", "segment.pump");
    private static final Set<String> SEGMENT_COLLECTION_ONLY = Set.of("segment.create", "segment.pump");
    private static final Set<String> RULE_CAPABILITIES = Set.of("rule.read", "rule.create", "rule.publish");
    private static final Set<String> ENTITLEMENT_CAPABILITIES = Set.of("entitlement.read", "entitlement.resolve");
    // 字典定义、会员创建和租户级行为重建没有单个已有会员目标，只能使用集合许可。
    private static final Set<String> MEMBER_COLLECTION_ONLY = Set.of("member.create", "member_tag.define", "member_behavior.rebuild");
    private static final Set<String> CREATION = Set.of("merchant.create", "store.create");
    private static final long SYNC_MAX_SECONDS = 60;
    // 手工刷新可扫描至既有最大TTL；只有refresh延长引用，其他入口仍是同步能力。
    private static final long SEGMENT_REFRESH_MAX_SECONDS = 86400L + SYNC_MAX_SECONDS;
    // 发放来源覆盖原七天截止；撤回使用独立有限窗口，到期不表示补偿业务已完成。
    private static final long COUPON_DELIVERY_EXECUTION_MAX_SECONDS = 7 * 86400L + SYNC_MAX_SECONDS;
    private static final long MAX_SECONDS = 37 * 86400L + 60;
    private final ExecutionMapper mapper;
    private final ReliableAuthorization access;
    private final TransactionTemplate tx;
    public ExecutionAuthorization(ExecutionMapper mapper, ReliableAuthorization access, TransactionTemplate tx) {
        this.mapper=mapper; this.access=access; this.tx=tx;
    }
    /** 签发先做严格实时判权；数据库事务仅保存引用，不包围远程图调用。 */
    public Reference issue(AccessContext context, Issue input) {
        if(input==null||input.check()==null)throw error(INVALID_ARGUMENT);
        var check=input.check(); BootstrapCommand.uuid(check.requestId());
        boolean inventory = SYNCHRONOUS_INVENTORY.stream().anyMatch(suffix -> (context.applicationId() + "." + suffix).equals(check.capability()));
        String scopedType = scopedType(context, check.capability());
        boolean scoped = scopedType != null;
        boolean expectedResource = scoped ? scopedType.equals(check.resourceType()) : ScopeDtos.STORE_RESOURCE_TYPE.equals(check.resourceType());
        if(!context.tenantId().equals(check.tenantId())||(!inventory && !scoped && !(context.applicationId()+".catalog.operate").equals(check.capability()))||!expectedResource
            ||!com.lrj.authz.governance.domain.IdentityModels.PrincipalKind.HUMAN.code().equals(context.actorType())
            ||(check.expectedMembershipGeneration()!=null&&check.expectedMembershipGeneration()!=context.membershipGeneration()))throw error(ACCESS_DENIED);
        Instant until;
        try { until=Instant.parse(input.expiresAt()); } catch(RuntimeException e) { throw error(INVALID_ARGUMENT); }
        Instant now=mapper.now();
        long maximumSeconds;
        if ((context.applicationId() + ".segment.refresh").equals(check.capability())) maximumSeconds = SEGMENT_REFRESH_MAX_SECONDS;
        else if (COUPON_DELIVERY_DURABLE_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(check.capability())))
            maximumSeconds = COUPON_DELIVERY_EXECUTION_MAX_SECONDS;
        else maximumSeconds = inventory || scoped ? SYNC_MAX_SECONDS : MAX_SECONDS;
        if(until.getNano()%1000!=0||!until.isAfter(now)||until.isAfter(now.plusSeconds(maximumSeconds)))throw error(INVALID_ARGUMENT);
        var result=access.evaluate(context,check.capability(),check.resourceType());
        var paths = permitted(context, check.capability(), result.alternatives());
        if(paths.isEmpty())throw error(ACCESS_DENIED);
        String fingerprint=AccessValues.hash(context.principalId(),context.membershipId(),context.membershipGeneration(),context.membershipVersion(),context.principalVersion(),
            context.tenantId(),context.applicationId(),context.environment(),context.callerServiceId(),check.capability(),check.resourceType(),until);
        Instant expiry=until;
        return tx.execute(status->{
            mapper.lock(context.membershipId());
            var old=mapper.request(context.callerServiceId(),check.requestId());
            if(old!=null) {
                if(!old.fingerprint().equals(fingerprint))throw error(INVALID_ARGUMENT);
                return reference(old);
            }
            if(mapper.active(context.membershipId())>=10000)throw error(DEPENDENCY_UNAVAILABLE);
            var row=new ExecutionMapper.Row(UUID.randomUUID().toString(),context.callerServiceId(),context.applicationId(),context.environment(),context.membershipId(),
                check.requestId(),fingerprint,write(context),check.capability(),check.resourceType(),write(new Paths(result.stamp().directoryId(),result.stamp().directoryEpoch(),paths)),expiry);
            if(mapper.insert(row)!=1)throw error(DEPENDENCY_UNAVAILABLE);
            return reference(row);
        });
    }
    /** 当前路径须与签发路径完全相同；撤权后重授的不同Grant不能复活排队任务。 */
    public ResourceDecision check(CallerService caller, Check input) {
        if(input==null||input.resource()==null||input.resource().check()==null)throw error(INVALID_ARGUMENT);
        BootstrapCommand.uuid(input.executionId());
        var row=mapper.find(input.executionId()); var request=input.resource().check(); var facts=input.resource().facts();
        BootstrapCommand.uuid(request.requestId());
        if(row==null||!caller.callerServiceId().equals(row.caller())||!caller.applicationId().equals(row.application())||!caller.environment().equals(row.environment()))throw error(ACCESS_DENIED);
        var context=read(row.contextJson(),AccessContext.class);
        if(!row.expiresAt().isAfter(mapper.now())||!context.tenantId().equals(request.tenantId())||!row.capability().equals(request.capability())||!row.resourceType().equals(request.resourceType())
            ||(request.expectedMembershipGeneration()!=null&&request.expectedMembershipGeneration()!=context.membershipGeneration()))throw error(ACCESS_DENIED);
        // 执行事实必须与引用绑定类型一致；会员不能借门店/部门字段制造细粒度授权。
        boolean member = ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE.equals(row.resourceType());
        boolean policy = ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE.equals(row.resourceType());
        boolean offer = ScopeDtos.POINT_OFFER_RESOURCE_TYPE.equals(row.resourceType());
        boolean couponDefinition = ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE.equals(row.resourceType());
        boolean couponDelivery = ScopeDtos.COUPON_DELIVERY_RESOURCE_TYPE.equals(row.resourceType());
        boolean entitlementDefinition = ScopeDtos.ENTITLEMENT_DEFINITION_RESOURCE_TYPE.equals(row.resourceType());
        boolean rule = ScopeDtos.MARKETING_RULE_RESOURCE_TYPE.equals(row.resourceType());
        boolean audience = ScopeDtos.MARKETING_AUDIENCE_RESOURCE_TYPE.equals(row.resourceType());
        boolean segment = ScopeDtos.MARKETING_SEGMENT_RESOURCE_TYPE.equals(row.resourceType());
        boolean campaign = ScopeDtos.MARKETING_CAMPAIGN_RESOURCE_TYPE.equals(row.resourceType());
        boolean entitlement = ScopeDtos.ENTITLEMENT_RESOURCE_TYPE.equals(row.resourceType());
        if(facts == null || !context.tenantId().equals(facts.tenantId()) || !row.resourceType().equals(facts.resourceType())
                || (!member && !policy && !offer && !couponDefinition && !couponDelivery && !entitlementDefinition && !entitlement && !rule && !audience && !segment && !campaign && !ScopeDtos.STORE_RESOURCE_TYPE.equals(facts.resourceType())) || !ScopeResourceBindings.validFacts(facts)) throw error(INVALID_ARGUMENT);
        // 政策、券/权益定义和人群快照只提供版本目录/追加创建集合许可，不构造成员或单个版本事实。
        if(policy || couponDefinition || entitlementDefinition || audience) throw error(ACCESS_DENIED);
        if(member && (!MEMBER_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(row.capability()))
                || MEMBER_COLLECTION_ONLY.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(row.capability())))) throw error(ACCESS_DENIED);
        // 兑换规则定义还没有已有对象，只能使用集合许可；停启必须绑定真实商品事实。
        if(offer && (!POINT_OFFER_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(row.capability()))
                || (context.applicationId() + ".point_offer.define").equals(row.capability()))) throw error(ACCESS_DENIED);
        // 权益处理只绑定实际授予记录；定义或其他能力不能借用此对象入口。
        if(entitlement && ENTITLEMENT_CAPABILITIES.stream().noneMatch(s -> (context.applicationId() + "." + s).equals(row.capability()))) throw error(ACCESS_DENIED);
        // 新规则创建只有集合许可；读取或发布绑定真实资产版本，不把版本当作状态修订。
        if(rule && (RULE_CAPABILITIES.stream().noneMatch(s -> (context.applicationId() + "." + s).equals(row.capability()))
                || (context.applicationId() + ".rule.create").equals(row.capability()))) throw error(ACCESS_DENIED);
        // 目录、创建和预算列表仅集合；六个已有活动动作绑定实际不可变内容版本。
        if(campaign && (CAMPAIGN_CAPABILITIES.stream().noneMatch(s -> (context.applicationId() + "." + s).equals(row.capability()))
                || CAMPAIGN_COLLECTION_ONLY.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(row.capability())))) throw error(ACCESS_DENIED);
        // 新定义和人工泵只有集合许可；运行控制由Owner提供父人群及固定定义版本。
        if(segment && (SEGMENT_CAPABILITIES.stream().noneMatch(s -> (context.applicationId() + "." + s).equals(row.capability()))
                || SEGMENT_COLLECTION_ONLY.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(row.capability())))) throw error(ACCESS_DENIED);
        // 创建/人工推进只有集合许可，读回执和控制使用Owner不可变批次内容事实。
        if(couponDelivery && (COUPON_DELIVERY_CAPABILITIES.stream().noneMatch(s -> (context.applicationId() + "." + s).equals(row.capability()))
                || COUPON_DELIVERY_COLLECTION_ONLY.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(row.capability())))) throw error(ACCESS_DENIED);
        var current=access.evaluate(context,row.capability(),row.resourceType());
        var original=read(row.pathsJson(),Paths.class);
        // 目录资格可能先移除再恢复同一个组Grant；绑定目录版本，防止旧后台任务借此复活。
        if(!original.directoryId().equals(current.stamp().directoryId())||original.directoryEpoch()!=current.stamp().directoryEpoch())throw error(ACCESS_DENIED);
        var eligible=current.alternatives().stream().filter(original.alternatives()::contains).toList();
        boolean allowed=ScopeRules.matches(context.tenantId(),context.principalId(),eligible,facts);
        Instant until=current.validUntil().isBefore(row.expiresAt())?current.validUntil():row.expiresAt();
        return new ResourceDecision("1",request.requestId(),row.capability(),row.resourceType(),facts.resourceId(),facts.resourceVersion(),allowed?"ALLOW":"DENY",current.decisionId(),context,until.toString());
    }
    /** 集合只取得原Grant的完整交集；新授予不能扩大既有引用，创建只允许全租户路径。 */
    public ScopeAccessDtos.Plan scope(CallerService caller, ScopeCheck input) {
        if(input == null || input.check() == null) throw error(INVALID_ARGUMENT);
        BootstrapCommand.uuid(input.executionId());
        var request = input.check(); BootstrapCommand.uuid(request.requestId());
        var row = mapper.find(input.executionId());
        if(row == null || !caller.callerServiceId().equals(row.caller()) || !caller.applicationId().equals(row.application())
                || !caller.environment().equals(row.environment())) throw error(ACCESS_DENIED);
        var context = read(row.contextJson(), AccessContext.class);
        if(scopedType(context, row.capability()) == null || !row.expiresAt().isAfter(mapper.now())
                || !context.tenantId().equals(request.tenantId()) || !row.capability().equals(request.capability())
                || !row.resourceType().equals(request.resourceType())
                || (request.expectedMembershipGeneration() != null && request.expectedMembershipGeneration() != context.membershipGeneration())) throw error(ACCESS_DENIED);
        var current = access.evaluate(context, row.capability(), row.resourceType());
        var original = read(row.pathsJson(), Paths.class);
        if(!original.directoryId().equals(current.stamp().directoryId()) || original.directoryEpoch() != current.stamp().directoryEpoch()) throw error(ACCESS_DENIED);
        var eligible = permitted(context, row.capability(), current.alternatives().stream().filter(original.alternatives()::contains).toList());
        var stamp = current.stamp();
        Instant until = current.validUntil().isBefore(row.expiresAt()) ? current.validUntil() : row.expiresAt();
        return new ScopeAccessDtos.Plan("1", request.requestId(), row.capability(), row.resourceType(), eligible.isEmpty() ? "DENY" : "ALLOW",
                current.decisionId(), context, stamp.policyId(), stamp.policyEpoch(), stamp.directoryId(), stamp.directoryEpoch(),
                stamp.manifestVersion(), stamp.tenantVersion(), until.toString(), eligible);
    }
    /** 精确能力与类型绑定；名称相似或未知后缀不能取得执行权。 */
    private static String scopedType(AccessContext context, String capability) {
        if(MEMBER_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE;
        if(MEMBER_POLICY_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE;
        if(POINT_OFFER_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.POINT_OFFER_RESOURCE_TYPE;
        if(COUPON_DEFINITION_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE;
        if(COUPON_DELIVERY_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.COUPON_DELIVERY_RESOURCE_TYPE;
        if(ENTITLEMENT_DEFINITION_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.ENTITLEMENT_DEFINITION_RESOURCE_TYPE;
        if(RULE_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.MARKETING_RULE_RESOURCE_TYPE;
        if(CAMPAIGN_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.MARKETING_CAMPAIGN_RESOURCE_TYPE;
        if(AUDIENCE_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.MARKETING_AUDIENCE_RESOURCE_TYPE;
        if(SEGMENT_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.MARKETING_SEGMENT_RESOURCE_TYPE;
        if(ENTITLEMENT_CAPABILITIES.stream().anyMatch(s -> (context.applicationId() + "." + s).equals(capability))) return ScopeDtos.ENTITLEMENT_RESOURCE_TYPE;
        return DIRECTORY.entrySet().stream().filter(e -> (context.applicationId() + "." + e.getKey()).equals(capability)).map(Map.Entry::getValue).findFirst().orElse(null);
    }
    /** 创建资源不能拼接若干指定资源路径当成对未来对象的全租户授权。 */
    private static List<Alternative> permitted(AccessContext context, String capability, List<Alternative> paths) {
        if(!ScopeDtos.COMMERCE_MEMBER_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.COMMERCE_MEMBER_POLICY_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.POINT_OFFER_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.COUPON_DEFINITION_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.COUPON_DELIVERY_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.ENTITLEMENT_DEFINITION_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.ENTITLEMENT_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.MARKETING_RULE_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.MARKETING_AUDIENCE_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.MARKETING_CAMPAIGN_RESOURCE_TYPE.equals(scopedType(context, capability))
                && !ScopeDtos.MARKETING_SEGMENT_RESOURCE_TYPE.equals(scopedType(context, capability))
                && CREATION.stream().noneMatch(s -> (context.applicationId() + "." + s).equals(capability))) return paths;
        return paths.stream().filter(a -> a.clauses().size() == 1 && a.clauses().getFirst().kind() == ScopeDtos.Kind.TENANT_ALL).toList();
    }
    private record Paths(String directoryId,long directoryEpoch,List<Alternative> alternatives) {}
    private static Reference reference(ExecutionMapper.Row row) { return new Reference("1",row.requestId(),row.id(),read(row.contextJson(),AccessContext.class),row.capability(),row.resourceType(),row.expiresAt().toString()); }
    private static String write(Object value) { try{return JSON.writeValueAsString(value);}catch(Exception e){throw error(DEPENDENCY_UNAVAILABLE);} }
    private static <T>T read(String value,Class<T> type) { try{return JSON.readValue(value,type);}catch(Exception e){throw error(AUTHZ_STATE_NOT_READY);} }
    private static GovernanceException error(GovernanceException.Code code) { return new GovernanceException(code); }
}
