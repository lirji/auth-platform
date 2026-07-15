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
import java.util.Set;

/**
 * Casdoor 组树 -> SpiceDB {@code department} 的差量同步（部门层级授权模型，取代 D3）。写三类 direct tuple:
 * {@code department:<t>_<g>#member@user:<sub>}、{@code #parent@department:<t>_<parent>}、{@code #admin@user:<sub>}。
 * 期望态(Casdoor {@link CasdoorClient#departmentSnapshot}) vs 当前态(SpiceDB direct tuple) 求差, 幂等可反复跑。
 *
 * <p>与 {@link GroupSyncService}（同步旧 {@code group} definition）<strong>并存</strong>: 二者写不同 definition，互不影响。
 * <p>正确性同 GroupSync: 只对账 <em>direct</em> tuple（{@code readRelationships}，不展开嵌套）; 一轮 DELETE 超阈值中止整轮
 * （防 Casdoor 拉取不全触发大面积撤权）。parent 单值: 期望态至多一个父。
 */
public class DepartmentSyncService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentSyncService.class);

    private final CasdoorClient casdoor;
    private final AuthzEngine engine;
    /** 一轮允许的最大 DELETE 数; 超过则中止整轮。<0 表示不限制。 */
    private final int deleteThreshold;

    public DepartmentSyncService(CasdoorClient casdoor, AuthzEngine engine) {
        this(casdoor, engine, -1);
    }

    public DepartmentSyncService(CasdoorClient casdoor, AuthzEngine engine, int deleteThreshold) {
        this.casdoor = casdoor;
        this.engine = engine;
        this.deleteThreshold = deleteThreshold;
    }

    public record SyncSummary(int departments, int added, int removed) {
    }

    public synchronized SyncSummary sync() {
        CasdoorClient.DepartmentSnapshot snap = casdoor.departmentSnapshot();
        Set<String> depts = new LinkedHashSet<>(snap.deptIds());
        depts.addAll(snap.members().keySet());
        depts.addAll(snap.admins().keySet());
        depts.addAll(snap.parents().keySet());

        List<RelationshipUpdate> touches = new ArrayList<>();
        List<RelationshipUpdate> deletes = new ArrayList<>();
        for (String d : depts) {
            ResourceRef dept = ResourceRef.of("department", d);
            diffUserRelation(dept, "member", snap.members().getOrDefault(d, Set.of()), touches, deletes);
            diffUserRelation(dept, "admin", snap.admins().getOrDefault(d, Set.of()), touches, deletes);
            diffParent(dept, snap.parents().get(d), touches, deletes);
        }

        // 删除熔断: 超阈值直接中止, 不写任何变更（TOUCH 也不写, 保持整轮"要么全做要么不做"）。
        if (deleteThreshold >= 0 && deletes.size() > deleteThreshold) {
            log.error("部门同步中止: 本轮 DELETE={} 超过阈值 {} (疑似 Casdoor 拉取不全/误配); 未写入任何变更",
                    deletes.size(), deleteThreshold);
            throw new IllegalStateException(
                    "department sync aborted: delete count " + deletes.size() + " exceeds threshold " + deleteThreshold);
        }

        List<RelationshipUpdate> updates = new ArrayList<>(touches);
        updates.addAll(deletes);
        if (!updates.isEmpty()) {
            engine.writeRelationships(updates);
        }
        log.info("Casdoor 部门同步: departments={} +{} -{}", depts.size(), touches.size(), deletes.size());
        return new SyncSummary(depts.size(), touches.size(), deletes.size());
    }

    /** member/admin 的 user 主体差量。 */
    private void diffUserRelation(ResourceRef dept, String relation, Set<String> want,
                                  List<RelationshipUpdate> touches, List<RelationshipUpdate> deletes) {
        Set<String> current = directUsers(dept, relation);
        for (String u : want) {
            if (!current.contains(u)) {
                touches.add(RelationshipUpdate.touch(dept, relation, SubjectRef.user(u)));
            }
        }
        for (String u : current) {
            if (!want.contains(u)) {
                deletes.add(RelationshipUpdate.delete(dept, relation, SubjectRef.user(u)));
            }
        }
    }

    /** parent 是单值 department 主体：期望态至多一个父; 与当前 direct parent tuple 求差（多余父边一律删）。 */
    private void diffParent(ResourceRef dept, String wantParentId,
                            List<RelationshipUpdate> touches, List<RelationshipUpdate> deletes) {
        Set<String> currentParents = directParents(dept);
        if (wantParentId != null && !currentParents.contains(wantParentId)) {
            touches.add(RelationshipUpdate.touch(dept, "parent", SubjectRef.of("department", wantParentId)));
        }
        for (String p : currentParents) {
            if (wantParentId == null || !p.equals(wantParentId)) {
                deletes.add(RelationshipUpdate.delete(dept, "parent", SubjectRef.of("department", p)));
            }
        }
    }

    /** 读某 department 某 relation 的 direct user 主体 (relation=null)。 */
    private Set<String> directUsers(ResourceRef dept, String relation) {
        Set<String> out = new LinkedHashSet<>();
        for (Relationship r : engine.readRelationships(RelationshipFilter.of("department", dept.id(), relation))) {
            SubjectRef s = r.subject();
            if ("user".equals(s.type()) && s.relation() == null) {
                out.add(s.id());
            }
        }
        return out;
    }

    /** 读某 department 的 direct parent (department 主体)。 */
    private Set<String> directParents(ResourceRef dept) {
        Set<String> out = new LinkedHashSet<>();
        for (Relationship r : engine.readRelationships(RelationshipFilter.of("department", dept.id(), "parent"))) {
            SubjectRef s = r.subject();
            if ("department".equals(s.type())) {
                out.add(s.id());
            }
        }
        return out;
    }
}
