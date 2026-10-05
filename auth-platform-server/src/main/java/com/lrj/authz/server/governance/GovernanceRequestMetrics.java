package com.lrj.authz.server.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.authz.governance.web.GovernanceWeb;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** 治理检查的有限维度指标；不缓存响应，不把主体、租户或请求URL作为指标标签。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name = "authz.governance.enabled", havingValue = "true")
public class GovernanceRequestMetrics extends OncePerRequestFilter {
    static final String OUTCOME = GovernanceRequestMetrics.class.getName() + ".outcome";
    private static final String PREFIX = "/internal/governance/v1/";
    private final MeterRegistry registry;

    /** 复用宿主Actuator注册表；指标端点暴露仍由运维配置决定。 */
    public GovernanceRequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    /** finally覆盖认证失败和未处理异常，错误永远不计作业务DENY或ALLOW。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            int status = response.getStatus();
            String outcome;
            if (!completed || status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                outcome = "ERROR";
            else if (status == HttpServletResponse.SC_UNAUTHORIZED) outcome = "AUTHN";
            else if (status == HttpServletResponse.SC_FORBIDDEN) outcome = "DENY";
            else if (status >= HttpServletResponse.SC_BAD_REQUEST) outcome = "INVALID";
            else
                outcome =
                        request.getAttribute(OUTCOME) instanceof String result ? result : "SUCCESS";
            Timer.builder("authz.governance.requests")
                    .description(
                            "Central governance HTTP duration by bounded operation and outcome")
                    .tags("operation", operation(request.getRequestURI()), "outcome", outcome)
                    .register(registry)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 只允许固定路径族，未知或攻击者生成的路径折叠为OTHER。 */
    static String operation(String path) {
        return switch (path) {
            case PREFIX + "context/resolve" -> "CONTEXT";
            case PREFIX + "access/check" -> "CHECK";
            case PREFIX + "access/check-bulk" -> "BULK";
            case PREFIX + "access/scope-plan" -> "SCOPE";
            case PREFIX + "access/check-resource" -> "RESOURCE";
            case PREFIX + "access/executions" -> "ISSUE";
            case PREFIX + "access/execution-check" -> "EXECUTION";
            default -> "OTHER";
        };
    }

    /** 在序列化前观察已确定的结果；无需复制包含身份上下文的HTTP响应。 */
    @RestControllerAdvice(annotations = GovernanceWeb.Endpoint.class)
    @ConditionalOnProperty(name = "authz.governance.enabled", havingValue = "true")
    public static class OutcomeAdvice implements ResponseBodyAdvice<Object> {
        @Override
        public boolean supports(
                MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
            return true;
        }

        @Override
        public Object beforeBodyWrite(
                Object body,
                MethodParameter parameter,
                MediaType media,
                Class<? extends HttpMessageConverter<?>> converter,
                ServerHttpRequest request,
                ServerHttpResponse response) {
            if (body instanceof JsonNode node
                    && request instanceof ServletServerHttpRequest servlet) {
                String result = decision(node);
                if (result != null) servlet.getServletRequest().setAttribute(OUTCOME, result);
            }
            return body;
        }

        /** 批量混合结果单独计数，避免一个允许掩盖同批拒绝或条件结果。 */
        static String decision(JsonNode node) {
            String value = node.path("decision").asText();
            if (value.equals("ALLOW") || value.equals("DENY") || value.equals("CONDITIONAL"))
                return value;
            if (node.path("results").isArray() && !node.path("results").isEmpty()) {
                String common = null;
                for (JsonNode item : node.path("results")) {
                    String next = decision(item);
                    if (next == null) return "ERROR";
                    common = common != null && !common.equals(next) ? "MIXED" : next;
                }
                return common;
            }
            return null;
        }
    }
}
