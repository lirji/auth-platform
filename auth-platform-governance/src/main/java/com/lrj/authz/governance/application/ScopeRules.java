package com.lrj.authz.governance.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.ScopeDtos.*;
import java.util.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.*;

/** 同Grant的有限范围解释器；资源Owner的SQL适配必须实现完全相同的交并语义。 */
public final class ScopeRules {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ScopeRules() {}

    /** 候选保持固定角色与固定范围的关联；图资格另由严格批量检查提供。 */
    public record Candidate(String grantId, List<String> capabilities, Rule rule) {}

    /** 规范化和防御复制让幂等摘要稳定，调用方不能在验证后改变集合。 */
    public static Rule validated(Rule input) {
        if (input == null || input.version() != 1 || input.resourceType() == null
                || input.clauses() == null || input.clauses().isEmpty() || input.clauses().size() > 4) throw invalid();
        CatalogManifest.code(input.resourceType());
        // 当前真实资源绑定为门店和商品；未确定拥有者/部门/供应商语义前绝不猜测字段。
        if (!Set.of(com.lrj.authz.protocol.ScopeDtos.STORE_RESOURCE_TYPE, com.lrj.authz.protocol.ScopeDtos.PRODUCT_RESOURCE_TYPE).contains(input.resourceType())) throw new GovernanceException(SCOPE_UNSUPPORTED);
        Set<Kind> seen = EnumSet.noneOf(Kind.class);
        List<Clause> clauses = new ArrayList<>();
        for (Clause c : input.clauses()) {
            if (c == null || c.kind() == null || c.values() == null || !seen.add(c.kind())
                    || c.values().size() > 100 || (c.includeRoot() && c.kind() != Kind.DEPARTMENT_TREE)) throw invalid();
            boolean emptyRequired = c.kind() == Kind.TENANT_ALL || c.kind() == Kind.SELF;
            if (emptyRequired != c.values().isEmpty()) throw invalid();
            var values = new TreeSet<String>();
            for (String value : c.values()) {
                if (value == null || !value.matches("[A-Za-z0-9_:/.-]{1,100}") || !values.add(value)) throw invalid();
            }
            if (c.kind() != Kind.TENANT_ALL && c.kind() != Kind.SPECIFIED_STORES && c.kind() != Kind.SPECIFIED_RESOURCES)
                throw new GovernanceException(SCOPE_UNSUPPORTED);
            clauses.add(new Clause(c.kind(), List.copyOf(values), c.includeRoot()));
        }
        if (seen.contains(Kind.TENANT_ALL) && seen.size() != 1) throw invalid();
        clauses.sort(Comparator.comparing(c -> c.kind().name()));
        return new Rule(input.version(), input.resourceType(), List.copyOf(clauses));
    }

    /** 固定版本快照使用规范化JSON，不能依赖Map遍历顺序做内容摘要。 */
    public static String encode(Rule input) {
        try { return JSON.writeValueAsString(validated(input)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw invalid(); }
    }

    /** 数据库内容同样重新校验，未知版本或绑定不能悄悄扩大。 */
    public static Rule decode(String json) {
        if (json == null || json.length() > 65536) throw invalid();
        try { return validated(JSON.readValue(json, Rule.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw invalid(); }
    }

    /** 只有该Grant同时包含请求能力并通过图检查，才提供它自己的范围。 */
    public static List<Alternative> select(String capability, String type, List<Candidate> candidates, Set<String> eligible) {
        if (candidates.size() > 100) throw new GovernanceException(DEPENDENCY_UNAVAILABLE);
        List<Alternative> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!candidate.capabilities().contains(capability) || !eligible.contains(candidate.grantId())) continue;
            Rule rule = validated(candidate.rule());
            if (!type.equals(rule.resourceType())) continue;
            result.add(new Alternative(candidate.grantId(), rule.version(), rule.clauses()));
        }
        return List.copyOf(result);
    }

    /** 资源事实必须来自已认证Owner；租户边界在路径OR之外始终取AND。 */
    public static boolean matches(String tenant, String principal, List<Alternative> alternatives, Facts facts) {
        if (tenant == null || principal == null || facts == null || !tenant.equals(facts.tenantId())
                || facts.resourceId() == null || facts.resourceVersion() < 0 || alternatives.size() > 100) return false;
        for (Alternative alternative : alternatives) {
            Rule rule = validated(new Rule(alternative.scopeVersion(), facts.resourceType(), alternative.clauses()));
            if (rule.clauses().stream().allMatch(c -> switch (c.kind()) {
                case TENANT_ALL -> true;
                case SPECIFIED_STORES -> facts.storeId() != null && c.values().contains(facts.storeId());
                case SPECIFIED_RESOURCES -> c.values().contains(facts.resourceId());
                default -> false;
            })) return true;
        }
        return false;
    }

    private static GovernanceException invalid() { return new GovernanceException(INVALID_ARGUMENT); }
}
