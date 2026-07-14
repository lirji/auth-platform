package com.lrj.authz.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式方法级判权。切面从 {@link SubjectResolver} 取主体, 从名为 {@code resourceIdParam} 的方法参数取资源 id,
 * 调 AuthzEngine.check(subject, permission, resourceType:id); 不通过抛 {@link AccessDeniedException}。
 * 例: {@code @CheckAccess(permission="view", resourceType="document", resourceIdParam="docId")}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CheckAccess {
    String permission();

    String resourceType();

    String resourceIdParam();
}
