package com.lrj.authz.sdk;

import com.lrj.authz.protocol.SubjectRef;

/**
 * 当前主体解析 SPI。消费方实现: 从自己的安全上下文取当前用户。
 * 例: knowledge-service 里 {@code () -> SubjectRef.user(TenantContext.current().userId())}。
 * @CheckAccess 切面依赖它。
 */
@FunctionalInterface
public interface SubjectResolver {
    /** 从当前请求的可信身份上下文解析主体，不能从业务参数推断登录身份。 */
    SubjectRef currentSubject();
}
