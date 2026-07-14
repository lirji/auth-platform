package com.lrj.authz.protocol;

/** 一条关系元组(读回时用,如列某资源现存授予)。 */
public record Relationship(ResourceRef resource, String relation, SubjectRef subject) {
}
