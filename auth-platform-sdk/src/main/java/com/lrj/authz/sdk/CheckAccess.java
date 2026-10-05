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
    /** 指定现有权限编码，切面不能从方法名自行推断访问资格。 */
    String permission();

    /** 绑定既有资源类型，资源标识不能暗中替换判权模型。 */
    String resourceType();

    /** 明确资源身份参数名，切面只解析该参数而不扫描其他业务输入。 */
    String resourceIdParam();

    /** true 时判权用 FULLY_CONSISTENT（撤权/敏感授权变更立即生效）；默认 minimizeLatency（低延迟）。 */
    boolean fullyConsistent() default false;
}
