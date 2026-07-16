package com.lrj.authz.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * admin 安全:OIDC resource-server 校验 Casdoor JWT。
 * groups claim(全路径,shortName 归一化)→ 权限;写端点需 authz-admin,读端点需 authz-viewer/admin。
 * actuator/health 与 webhook 放行(webhook 走独立共享密钥,见 CasdoorSyncController)。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/admin/casdoor/webhook").permitAll()
                        // 写端点:需管理员
                        .requestMatchers(HttpMethod.POST,
                                "/admin/grants", "/admin/grants/revoke",
                                "/admin/casdoor/sync", "/admin/casdoor/sync-departments").hasAuthority("authz-admin")
                        // 读/调试端点:viewer 或 admin
                        .requestMatchers(HttpMethod.POST, "/admin/check", "/admin/expand").hasAnyAuthority("authz-admin", "authz-viewer")
                        .requestMatchers(HttpMethod.GET, "/admin/resources/**", "/admin/subjects/**", "/admin/schema",
                                "/admin/relationships", "/admin/audit")
                        .hasAnyAuthority("authz-admin", "authz-viewer")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /** JWT 解码器:JWKS 验签 + issuer + (可选)audience 校验。 */
    @Bean
    public JwtDecoder jwtDecoder(AdminSecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        validators.add(new JwtIssuerValidator(props.getIssuer()));
        if (props.getClientId() != null && !props.getClientId().isBlank()) {
            String aud = props.getClientId();
            validators.add(jwt -> jwt.getAudience().contains(aud)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "aud 需含 " + aud, null)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** 把 Casdoor 的 groups claim(如 "built-in/authz-admin")归一化为权限("authz-admin")。 */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            Object groups = jwt.getClaim("groups");
            if (groups instanceof Collection<?> col) {
                for (Object g : col) {
                    String s = String.valueOf(g);
                    int i = s.lastIndexOf('/');
                    authorities.add(new SimpleGrantedAuthority(i >= 0 ? s.substring(i + 1) : s));
                }
            }
            return authorities;
        });
        return converter;
    }
}
