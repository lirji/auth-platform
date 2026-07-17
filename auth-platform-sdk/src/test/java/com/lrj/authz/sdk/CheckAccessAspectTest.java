package com.lrj.authz.sdk;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckAccessAspectTest {

    private static final SubjectRef SUBJECT = SubjectRef.user("u1");

    private static class Fixture {
        @CheckAccess(permission = "view", resourceType = "document", resourceIdParam = "docId")
        Object guarded(String ignored, String docId) { return null; }

        @CheckAccess(permission = "delete", resourceType = "document", resourceIdParam = "docId",
                fullyConsistent = true)
        Object sensitive(String docId) { return null; }
    }

    private static CheckAccess annotation(String method, Class<?>... types) throws Exception {
        return Fixture.class.getDeclaredMethod(method, types).getAnnotation(CheckAccess.class);
    }

    private static ProceedingJoinPoint joinPoint(String[] names, Object[] args, Object returnValue) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getParameterNames()).thenReturn(names);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(returnValue);
        return pjp;
    }

    @Test
    void allowedProceedsWithResolvedNamedParameter() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        SubjectResolver resolver = mock(SubjectResolver.class);
        when(resolver.currentSubject()).thenReturn(SUBJECT);
        when(engine.check(SUBJECT, "view", ResourceRef.of("document", "42"), Consistency.minimizeLatency()))
                .thenReturn(true);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"ignored", "docId"}, new Object[]{"not-the-id", "42"}, "result");

        Object result = new CheckAccessAspect(engine, resolver).around(
                pjp, annotation("guarded", String.class, String.class));

        assertThat(result).isEqualTo("result");
        verify(resolver).currentSubject();
        verify(engine).check(SUBJECT, "view", ResourceRef.of("document", "42"), Consistency.minimizeLatency());
        verify(pjp).proceed();
    }

    @Test
    void fullyConsistentAnnotationUsesFullMode() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        SubjectResolver resolver = () -> SUBJECT;
        when(engine.check(any(), any(), any(), any())).thenReturn(true);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"docId"}, new Object[]{"d9"}, "ok");

        new CheckAccessAspect(engine, resolver).around(pjp, annotation("sensitive", String.class));

        ArgumentCaptor<Consistency> consistency = ArgumentCaptor.forClass(Consistency.class);
        verify(engine).check(any(), any(), any(), consistency.capture());
        assertThat(consistency.getValue()).isEqualTo(Consistency.fullyConsistent());
    }

    @Test
    void deniedNeverInvokesTarget() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenReturn(false);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"ignored", "docId"}, new Object[]{"x", "d1"}, "must-not-run");

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                new CheckAccessAspect(engine, () -> SUBJECT).around(
                        pjp, annotation("guarded", String.class, String.class)));

        assertThat(ex).hasMessageContaining("view").hasMessageContaining("document:d1");
        verify(pjp, never()).proceed();
    }

    @Test
    void missingParameterNameFailsBeforeCheck() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        ProceedingJoinPoint pjp = joinPoint(new String[]{"other"}, new Object[]{"d1"}, "unused");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new CheckAccessAspect(engine, () -> SUBJECT).around(
                        pjp, annotation("sensitive", String.class)));

        assertThat(ex).hasMessageContaining("docId");
        verify(engine, never()).check(any(), any(), any(), any());
        verify(pjp, never()).proceed();
    }

    @Test
    void engineFailureNeverInvokesTarget() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        when(engine.check(any(), any(), any(), any())).thenThrow(new IllegalStateException("dependency down"));
        ProceedingJoinPoint pjp = joinPoint(new String[]{"docId"}, new Object[]{"d1"}, "unused");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new CheckAccessAspect(engine, () -> SUBJECT).around(
                        pjp, annotation("sensitive", String.class)));

        assertThat(ex).hasMessageContaining("dependency down");
        verify(pjp, never()).proceed();
    }

    @Test
    void resolverFailureNeverInvokesEngineOrTarget() throws Throwable {
        AuthzEngine engine = mock(AuthzEngine.class);
        SubjectResolver resolver = mock(SubjectResolver.class);
        when(resolver.currentSubject()).thenThrow(new IllegalStateException("no subject in context"));
        ProceedingJoinPoint pjp = joinPoint(new String[]{"docId"}, new Object[]{"d1"}, "unused");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new CheckAccessAspect(engine, resolver).around(pjp, annotation("sensitive", String.class)));

        assertThat(ex).hasMessageContaining("no subject in context");
        verify(engine, never()).check(any(), any(), any(), any());
        verify(pjp, never()).proceed();
    }

    // TODO(issue-A01): names==null、args 长度不符、docId null/blank 应显式 fail-closed，修复后启用。
    // TODO(issue-A02): 当前没有 SpEL/下标契约，不得写成功解析 #request.id 的虚假测试。
}
