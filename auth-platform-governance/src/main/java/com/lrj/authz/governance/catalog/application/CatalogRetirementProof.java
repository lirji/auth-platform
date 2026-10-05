package com.lrj.authz.governance.catalog.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.protocol.CapabilityRetirementDtos.ProofState;

import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

/** 独立部署核验器的签名只读适配；Owner请求、普通发布声明不能充当运行证明。 */
public final class CatalogRetirementProof {
    private static final int MAX_BYTES = 131072, MAX_TARGETS = 100, MAX_SECONDS = 300;
    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PAYLOAD_FIELDS =
            Set.of(
                    "schema_version",
                    "application_id",
                    "capability",
                    "manifest_version",
                    "content_hash",
                    "presentation_hash",
                    "lifecycle_version",
                    "checked_at",
                    "valid_until",
                    "deployments");
    private static final Set<String> TARGET_FIELDS =
            Set.of(
                    "target_id",
                    "commit",
                    "artifact_hash",
                    "api_mapping_hash",
                    "complete",
                    "capability_references");
    private final Map<String, Authority> authorities;

    /** 配置目录／目标／公钥由宿主控制，文件内容每次重读以响应部署失效。 */
    public record Authority(
            String applicationId, Set<String> deploymentTargets, Path proofFile, PublicKey key) {
        /** 在不可变契约构造边界沿用既有字段校验、集合快照或默认值，保持各创建入口语义一致。 */
        public Authority {
            deploymentTargets = Set.copyOf(deploymentTargets);
        }
    }

    /** 原证明只在已验证时落盘；失败原因是稳定code，不回显路径或底层异常。 */
    public record Result(
            String state, String reason, String hash, String validUntil, String envelope) {
        /** 运行未核验时拒绝正常退役，仍允许弃用／紧急停用。 */
        public boolean proven() {
            return ProofState.PROVEN.code().equals(state);
        }
    }

    /** 空受控配置是明确UNPROVEN，不注册任何默认部署目标。 */
    public CatalogRetirementProof(List<Authority> input) {
        var values = new HashMap<String, Authority>();
        for (var a : input) {
            CatalogManifest.code(a.applicationId());
            if (a.deploymentTargets().isEmpty()
                    || a.deploymentTargets().size() > MAX_TARGETS
                    || !a.proofFile().isAbsolute()
                    || a.key() == null) throw new IllegalArgumentException("退役核验配置无效");
            a.deploymentTargets().forEach(CatalogManifest::code);
            if (values.put(a.applicationId(), a) != null)
                throw new IllegalArgumentException("退役核验配置重复");
        }
        authorities = Map.copyOf(values);
    }

    /** 严格配置漏count／未知字段拒绝，公钥不是Bearer或签名私钥。 */
    public static CatalogRetirementProof from(Properties p) {
        try {
            int count = Integer.parseInt(p.getProperty("catalog.retirement.count", "0"));
            if (count < 0 || count > MAX_TARGETS) throw new IllegalArgumentException();
            var values = new ArrayList<Authority>();
            for (int i = 1; i <= count; i++) {
                String prefix = "catalog.retirement." + i + ".";
                String raw = p.getProperty(prefix + "deployment-targets");
                var targets = Arrays.asList(raw.split(",", -1));
                if (new HashSet<>(targets).size() != targets.size())
                    throw new IllegalArgumentException();
                var key =
                        KeyFactory.getInstance("Ed25519")
                                .generatePublic(
                                        new X509EncodedKeySpec(
                                                Base64.getDecoder()
                                                        .decode(
                                                                p.getProperty(
                                                                        prefix + "public-key"))));
                values.add(
                        new Authority(
                                p.getProperty(prefix + "application-id"),
                                Set.copyOf(targets),
                                Path.of(p.getProperty(prefix + "proof-file")),
                                key));
            }
            for (String name : p.stringPropertyNames())
                if (name.startsWith("catalog.retirement.")
                        && !name.equals("catalog.retirement.count")) {
                    var parts = name.split("\\.");
                    if (parts.length != 4
                            || Integer.parseInt(parts[2]) < 1
                            || Integer.parseInt(parts[2]) > count
                            || !Set.of(
                                            "application-id",
                                            "deployment-targets",
                                            "public-key",
                                            "proof-file")
                                    .contains(parts[3])) throw new IllegalArgumentException();
                }
            return new CatalogRetirementProof(values);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("退役核验配置无效");
        }
    }

    /** 验签覆盖原字节；所有配置部署必须完整、零引用且绑定当前目录和生命周期。 */
    public Result verify(
            String app,
            String capability,
            long manifest,
            String content,
            String presentation,
            long lifecycle) {
        var authority = authorities.get(app);
        if (authority == null) return unproven("NOT_CONFIGURED");
        byte[] bytes;
        try (var in = Files.newInputStream(authority.proofFile())) {
            bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return unproven("INVALID_PROOF");
        } catch (java.io.IOException unavailable) {
            return unproven("PROOF_UNAVAILABLE");
        }
        try {
            var envelope = JSON.readTree(bytes);
            fields(envelope, Set.of("payload", "signature"));
            byte[] payload = Base64.getDecoder().decode(text(envelope, "payload"));
            var signature = Signature.getInstance("Ed25519");
            signature.initVerify(authority.key());
            signature.update(payload);
            if (!signature.verify(Base64.getDecoder().decode(text(envelope, "signature"))))
                return unproven("INVALID_SIGNATURE");
            var n = JSON.readTree(payload);
            fields(n, PAYLOAD_FIELDS);
            if (number(n, "schema_version") != 1
                    || !text(n, "application_id").equals(app)
                    || !text(n, "capability").equals(capability)
                    || number(n, "manifest_version") != manifest
                    || !text(n, "content_hash").equals(content)
                    || !text(n, "presentation_hash").equals(presentation)
                    || number(n, "lifecycle_version") != lifecycle)
                return unproven("BASIS_MISMATCH");
            Instant checked = Instant.parse(text(n, "checked_at")),
                    until = Instant.parse(text(n, "valid_until")),
                    now = Instant.now();
            if (checked.isAfter(now)
                    || !until.isAfter(checked)
                    || until.isAfter(checked.plusSeconds(MAX_SECONDS))
                    || !until.isAfter(now)) return unproven("EXPIRED_OR_INVALID_TIME");
            var deployments = n.get("deployments");
            if (deployments == null || !deployments.isArray() || deployments.size() > MAX_TARGETS)
                return unproven("INVALID_PROOF");
            var targets = new HashSet<String>();
            for (var d : deployments) {
                fields(d, TARGET_FIELDS);
                String target = text(d, "target_id");
                CatalogManifest.code(target);
                if (!targets.add(target)) return unproven("TARGET_MISMATCH");
                if (!text(d, "commit").matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")
                        || !text(d, "artifact_hash").matches("[a-f0-9]{64}")
                        || !text(d, "api_mapping_hash").matches("[a-f0-9]{64}"))
                    return unproven("INVALID_PROOF");
                if (!d.path("complete").isBoolean() || !d.path("complete").booleanValue())
                    return unproven("INCOMPLETE_MAPPING");
                if (number(d, "capability_references") != 0)
                    return unproven("API_REFERENCES_REMAIN");
            }
            if (!targets.equals(authority.deploymentTargets())) return unproven("TARGET_MISMATCH");
            return new Result(
                    ProofState.PROVEN.code(),
                    null,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    until.toString(),
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception invalid) {
            return unproven("INVALID_PROOF");
        }
    }

    private static Result unproven(String reason) {
        return new Result(ProofState.UNPROVEN.code(), reason, null, null, null);
    }

    private static void fields(JsonNode n, Set<String> expected) {
        if (n == null || !n.isObject() || n.size() != expected.size())
            throw new IllegalArgumentException();
        n.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!expected.contains(k)) throw new IllegalArgumentException();
                        });
    }

    private static String text(JsonNode n, String field) {
        if (!n.path(field).isTextual()) throw new IllegalArgumentException();
        return n.get(field).textValue();
    }

    private static long number(JsonNode n, String field) {
        if (!n.path(field).isIntegralNumber()
                || !n.path(field).canConvertToLong()
                || n.get(field).longValue() < 0) throw new IllegalArgumentException();
        return n.get(field).longValue();
    }
}
