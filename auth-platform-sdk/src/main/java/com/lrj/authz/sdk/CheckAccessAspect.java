package com.lrj.authz.sdk;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

/** @CheckAccess 的环绕切面: 判权不通过则抛 AccessDeniedException。 */
@Aspect
public class CheckAccessAspect {

    private final AuthzEngine engine;
    private final SubjectResolver subjectResolver;

    public CheckAccessAspect(AuthzEngine engine, SubjectResolver subjectResolver) {
        this.engine = engine;
        this.subjectResolver = subjectResolver;
    }

    @Around("@annotation(checkAccess)")
    public Object around(ProceedingJoinPoint pjp, CheckAccess checkAccess) throws Throwable {
        SubjectRef subject = subjectResolver.currentSubject();
        String resourceId = resolveParam(pjp, checkAccess.resourceIdParam());
        ResourceRef resource = ResourceRef.of(checkAccess.resourceType(), resourceId);
        boolean allowed = engine.check(subject, checkAccess.permission(), resource, Consistency.minimizeLatency());
        if (!allowed) {
            throw new AccessDeniedException(
                    "拒绝: subject=" + subject + " 无 " + checkAccess.permission() + " 权限 on " + resource.ref());
        }
        return pjp.proceed();
    }

    private String resolveParam(ProceedingJoinPoint pjp, String paramName) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(paramName)) {
                return String.valueOf(args[i]);
            }
        }
        throw new IllegalStateException("@CheckAccess.resourceIdParam=\"" + paramName + "\" 未在方法参数中找到");
    }
}
