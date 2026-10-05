package com.lrj.authz.protocol;

/** 受保护对象引用: 类型 + id。例如 document:d_42 -> ResourceRef("document","d_42")。 */
public record ResourceRef(String type, String id) {
    /** 用明确类型、身份及原过滤字段建立 ResourceRef，不能仅从标识推断访问资格。 */
    public static ResourceRef of(String type, String id) {
        return new ResourceRef(type, id);
    }

    /** SpiceDB 对象引用形式 type:id。 */
    public String ref() {
        return type + ":" + id;
    }
}
