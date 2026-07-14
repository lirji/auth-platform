package com.lrj.authz.admin.casdoor;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
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
 * Casdoor 组成员 -> SpiceDB {@code group:<g>#member@user:<uid>} 元组的差量同步。
 * 期望态(Casdoor) vs 当前态(SpiceDB) 求差: 新增写 TOUCH, 移除写 DELETE。幂等, 可反复跑(reconcile)。
 */
public class GroupSyncService {

    private static final Logger log = LoggerFactory.getLogger(GroupSyncService.class);

    private final CasdoorClient casdoor;
    private final AuthzEngine engine;

    public GroupSyncService(CasdoorClient casdoor, AuthzEngine engine) {
        this.casdoor = casdoor;
        this.engine = engine;
    }

    public record SyncSummary(int groups, int added, int removed) {
    }

    public synchronized SyncSummary sync() {
        Map<String, Set<String>> desired = casdoor.groupMembers();
        Set<String> groups = new LinkedHashSet<>(casdoor.groupNames());
        groups.addAll(desired.keySet());

        List<RelationshipUpdate> updates = new ArrayList<>();
        int added = 0;
        int removed = 0;
        for (String g : groups) {
            ResourceRef group = ResourceRef.of("group", g);
            Set<String> current = new LinkedHashSet<>(engine
                    .lookupSubjects(group, "member", "user", Consistency.fullyConsistent())
                    .stream().map(SubjectRef::id).toList());
            Set<String> want = desired.getOrDefault(g, Set.of());
            for (String u : want) {
                if (!current.contains(u)) {
                    updates.add(RelationshipUpdate.touch(group, "member", SubjectRef.user(u)));
                    added++;
                }
            }
            for (String u : current) {
                if (!want.contains(u)) {
                    updates.add(RelationshipUpdate.delete(group, "member", SubjectRef.user(u)));
                    removed++;
                }
            }
        }
        if (!updates.isEmpty()) {
            engine.writeRelationships(updates);
        }
        log.info("Casdoor 组同步: groups={} +{} -{}", groups.size(), added, removed);
        return new SyncSummary(groups.size(), added, removed);
    }
}
