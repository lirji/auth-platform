package com.lrj.authz.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolValueObjectsTest {

    @Test
    void resourceFactoryAndRef() {
        ResourceRef resource = ResourceRef.of("document", "d1");
        assertThat(resource.type()).isEqualTo("document");
        assertThat(resource.id()).isEqualTo("d1");
        assertThat(resource.ref()).isEqualTo("document:d1");
        assertThat(resource).isEqualTo(new ResourceRef("document", "d1"));
    }

    @Test
    void subjectFactoriesPreserveUserset() {
        assertThat(SubjectRef.user("u1")).isEqualTo(new SubjectRef("user", "u1", null));
        assertThat(SubjectRef.of("service", "s1")).isEqualTo(new SubjectRef("service", "s1", null));
        assertThat(SubjectRef.ofRelation("group", "acme_eng", "member"))
                .isEqualTo(new SubjectRef("group", "acme_eng", "member"));
    }

    @Test
    void relationshipHasValueSemantics() {
        Relationship relationship = new Relationship(
                ResourceRef.of("document", "d1"), "viewer", SubjectRef.user("u1"));
        assertThat(relationship).isEqualTo(new Relationship(
                new ResourceRef("document", "d1"), "viewer", new SubjectRef("user", "u1", null)));
    }

    @Test
    void updateFactoriesSelectExactOperation() {
        ResourceRef resource = ResourceRef.of("document", "d1");
        SubjectRef subject = SubjectRef.user("u1");
        assertThat(RelationshipUpdate.create(resource, "viewer", subject).operation())
                .isEqualTo(RelationshipUpdate.Operation.CREATE);
        assertThat(RelationshipUpdate.touch(resource, "viewer", subject).operation())
                .isEqualTo(RelationshipUpdate.Operation.TOUCH);
        assertThat(RelationshipUpdate.delete(resource, "viewer", subject).operation())
                .isEqualTo(RelationshipUpdate.Operation.DELETE);
    }

    @Test
    void filterFactoriesPreserveWildcards() {
        ResourceRef resource = ResourceRef.of("document", "d1");
        assertThat(RelationshipFilter.ofResource(resource))
                .isEqualTo(new RelationshipFilter("document", "d1", null));
        assertThat(RelationshipFilter.of("document", null, "viewer"))
                .isEqualTo(new RelationshipFilter("document", null, "viewer"));
    }

    @Test
    void consistencyFactoriesEncodeModes() {
        assertThat(Consistency.minimizeLatency())
                .isEqualTo(new Consistency(Consistency.Mode.MINIMIZE_LATENCY, null));
        assertThat(Consistency.fullyConsistent())
                .isEqualTo(new Consistency(Consistency.Mode.FULLY_CONSISTENT, null));
        assertThat(Consistency.atLeastAsFresh("zed-1"))
                .isEqualTo(new Consistency(Consistency.Mode.AT_LEAST_AS_FRESH, "zed-1"));
    }

    @Test
    void zedTokenHasValueSemantics() {
        assertThat(new ZedTokenView("zed-1")).isEqualTo(new ZedTokenView("zed-1"));
    }

    // TODO(issue-ADM02): 修复 compact constructor 后，为每类补 null/blank/非法组合 assertThrows。
}
