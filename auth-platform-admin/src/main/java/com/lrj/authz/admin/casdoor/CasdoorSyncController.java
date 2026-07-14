package com.lrj.authz.admin.casdoor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Casdoor 同步入口: 手动触发(需 authz-admin,由 SecurityConfig 把守)+ webhook(机器回调,独立共享密钥)。
 * 未启用(authz.casdoor.enabled=false)时返回 409。
 */
@RestController
@RequestMapping("/admin/casdoor")
public class CasdoorSyncController {

    private final ObjectProvider<GroupSyncService> syncProvider;
    private final String webhookSecret;

    public CasdoorSyncController(ObjectProvider<GroupSyncService> syncProvider,
                                 @Value("${authz.security.webhook-secret:}") String webhookSecret) {
        this.syncProvider = syncProvider;
        this.webhookSecret = webhookSecret;
    }

    /** 手动全量同步(经用户 token,SecurityConfig 要求 authz-admin)。 */
    @PostMapping("/sync")
    public ResponseEntity<?> sync() {
        GroupSyncService sync = syncProvider.getIfAvailable();
        if (sync == null) {
            return ResponseEntity.status(409).body(Map.of("error", "authz.casdoor.enabled=false"));
        }
        return ResponseEntity.ok(sync.sync());
    }

    /**
     * Casdoor webhook: 用户/组变更事件触发一次全量同步(差量幂等兜底)。
     * SecurityConfig 放行本端点(非用户 token),改用共享密钥头 X-Webhook-Secret 校验(常量时间比较)。
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
                                     @RequestBody(required = false) String body) {
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (secret == null || !MessageDigest.isEqual(
                    secret.getBytes(StandardCharsets.UTF_8), webhookSecret.getBytes(StandardCharsets.UTF_8))) {
                return ResponseEntity.status(401).body(Map.of("error", "invalid webhook secret"));
            }
        }
        GroupSyncService sync = syncProvider.getIfAvailable();
        if (sync == null) {
            return ResponseEntity.status(409).body(Map.of("error", "disabled"));
        }
        return ResponseEntity.ok(Map.of("synced", true, "summary", sync.sync()));
    }
}
