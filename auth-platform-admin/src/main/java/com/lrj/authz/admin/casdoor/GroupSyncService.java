package com.lrj.authz.admin.casdoor;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Relationship;
import com.lrj.authz.protocol.RelationshipFilter;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Casdoor 组成员 -> SpiceDB {@code group:<tenant>_<group>#member@user:<uid>} 元组的差量同步。
 * 期望态(Casdoor) vs 当前态(SpiceDB) 求差: 新增写 TOUCH, 移除写 DELETE。幂等, 可反复跑(reconcile)。
 *
 * <p>两处关键正确性:
 * <ul>
 *   <li><strong>只对账 direct membership</strong>: 当前态用 {@link AuthzEngine#readRelationships} 读<em>直接</em>
 *       tuple, 只认 {@code subject.type=user 且 relation=null} 的成员; 不用会展开嵌套/计算成员的
 *       {@code lookupSubjects(permission)}, 否则会把嵌套组的间接成员误当作可删除的 direct tuple。</li>
 *   <li><strong>删除熔断</strong>: 一轮 DELETE 数超过阈值时中止整轮 (不写任何变更), 防 Casdoor 拉取不全/误配
 *       触发大面积撤权。</li>
 * </ul>
 */
public class GroupSyncService {

    private static final Logger log = LoggerFactory.getLogger(GroupSyncService.class);

    private final CasdoorClient casdoor;
    private final AuthzEngine engine;
    /** 一轮允许的最大 DELETE 数; 超过则中止整轮。<0 表示不限制。 */
    private final int deleteThreshold;

    public GroupSyncService(CasdoorClient casdoor, AuthzEngine engine) {
        this(casdoor, engine, -1);
    }

    public GroupSyncService(CasdoorClient casdoor, AuthzEngine engine, int deleteThreshold) {
        this.casdoor = casdoor;
        this.engine = engine;
        this.deleteThreshold = deleteThreshold;
    }

    public record SyncSummary(int groups, int added, int removed) {
    }

    public synchronized SyncSummary sync() {
        Map<String, Set<String>> desired = casdoor.groupMembers();
        Set<String> groups = new LinkedHashSet<>(casdoor.groupNames());
        groups.addAll(desired.keySet());

        List<RelationshipUpdate> touches = new ArrayList<>();
        List<RelationshipUpdate> deletes = new ArrayList<>();
        for (String g : groups) {
            ResourceRef group = ResourceRef.of("group", g);
            Set<String> current = directMembers(group);
            Set<String> want = desired.getOrDefault(g, Set.of());
            for (String u : want) {
                if (!current.contains(u)) {
                    touches.add(RelationshipUpdate.touch(group, "member", SubjectRef.user(u)));
                }
            }
            for (String u : current) {
                if (!want.contains(u)) {
                    deletes.add(RelationshipUpdate.delete(group, "member", SubjectRef.user(u)));
                }
            }
        }

        // 删除熔断: 超阈值直接中止, 不写任何变更 (TOUCH 也不写, 保持整轮原子的"要么全做要么不做"直觉)。
        if (deleteThreshold >= 0 && deletes.size() > deleteThreshold) {
            log.error("Casdoor 组同步中止: 本轮 DELETE={} 超过阈值 {} (疑似 Casdoor 拉取不全/误配); 未写入任何变更",
                    deletes.size(), deleteThreshold);
            throw new IllegalStateException(
                    "group sync aborted: delete count " + deletes.size() + " exceeds threshold " + deleteThreshold);
        }

        List<RelationshipUpdate> updates = new ArrayList<>(touches);
        updates.addAll(deletes);
        if (!updates.isEmpty()) {
            engine.writeRelationships(updates);
        }
        log.info("Casdoor 组同步: groups={} +{} -{}", groups.size(), touches.size(), deletes.size());
        return new SyncSummary(groups.size(), touches.size(), deletes.size());
    }

    /** 读某组的 direct membership: 只认直接 user 主体 (relation=null), 排除嵌套组等间接成员。 */
    private Set<String> directMembers(ResourceRef group) {
        Set<String> out = new LinkedHashSet<>();
        for (Relationship r : engine.readRelationships(RelationshipFilter.of("group", group.id(), "member"))) {
            SubjectRef s = r.subject();
            if ("user".equals(s.type()) && s.relation() == null) {
                out.add(s.id());
            }
        }
        return out;
    }
}
