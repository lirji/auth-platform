package com.lrj.authz.governance.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.lrj.authz.governance.domain.CatalogModels.*;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.lrj.authz.governance.application.GovernanceException.Code.INVALID_ARGUMENT;

/** 清单边界先限制输入，再校验引用和不可变语义，避免把任意JSON变成权限。 */
public final class CatalogManifest {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private CatalogManifest() {}
    /** 拒绝超限、未知字段、重复键和宽松类型转换。 */
    public static Manifest read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(131073);
            if (bytes.length > 131072) { throw invalid(); }
            return normalize(JSON.readValue(bytes, Manifest.class));
        } catch (Exception failure) { throw invalid(); }
    }
    /** 存量快照也按同一契约解析，不放松损坏数据。 */
    public static Manifest read(String json) {
        return read(new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    /** 归一化排序使内容摘要不受输入顺序、缩进或重复引用影响。 */
    public static Manifest normalize(Manifest m) {
        if (m == null || !"1".equals(m.schemaVersion()) || m.manifestVersion() < 1) { throw invalid(); }
        code(m.application());
        if (m.capabilities() == null || m.capabilities().isEmpty() || m.capabilities().size() > 200 || m.menus() == null || m.menus().size() > 100) { throw invalid(); }
        Set<String> caps = new HashSet<>();
        for (Capability c : m.capabilities()) {
            if (c == null) { throw invalid(); } code(c.code()); code(c.resourceType());
            if (!c.code().startsWith(m.application() + ".") || c.riskLevel() == null || !caps.add(c.code())) { throw invalid(); }
        }
        Map<String,Menu> menus = new HashMap<>();
        Set<Integer> positions = new HashSet<>();
        for (Menu n : m.menus()) {
            if (n == null) { throw invalid(); } code(n.code());
            if ((n.label() == null) != (n.position() == null)) { throw invalid(); }
            if (n.label() != null && (n.label().isBlank() || !n.label().equals(n.label().strip())
                    || n.label().codePointCount(0,n.label().length()) > 80
                    || n.label().codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>')
                    || n.position() < 0 || n.position() >= 100 || !positions.add(n.position()))) { throw invalid(); }
            if (menus.put(n.code(), n) != null || n.anyOf() == null || n.anyOf().size() > 200
                    || new HashSet<>(n.anyOf()).size() != n.anyOf().size() || !caps.containsAll(n.anyOf())) { throw invalid(); }
            if (n.route() != null && (!n.route().matches("/[a-zA-Z0-9/_-]*") || n.route().contains("//"))) { throw invalid(); }
            if (n.route() != null && n.anyOf().isEmpty()) { throw invalid(); }
        }
        for (Menu n : m.menus()) {
            Set<String> path = new HashSet<>();
            Menu current = n;
            while (current != null) {
                if (!path.add(current.code())) { throw invalid(); }
                String parent = current.parent();
                if (parent != null && !menus.containsKey(parent)) { throw invalid(); }
                current = parent == null ? null : menus.get(parent);
            }
        }
        return new Manifest("1", m.application(), m.manifestVersion(), m.capabilities().stream().sorted(Comparator.comparing(Capability::code)).toList(),
                m.menus().stream().map(n -> new Menu(n.code(), n.parent(), n.route(), n.anyOf().stream().sorted().toList(), n.label(), n.position())).sorted(Comparator.comparing(Menu::code)).toList());
    }
    /** 仅接受稳定命名空间标识，禁止动态权限表达式。 */
    public static void code(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9._-]{0,99}")) { throw invalid(); }
    }
    /** 登记入口源禁止凭据、片段与任意URL scheme；HTTP仅用于回环隔离开发。 */
    public static void origin(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty()) || value.length() > 500
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1").contains(uri.getHost())))) { throw invalid(); }
        } catch (RuntimeException failure) { throw invalid(); }
    }
    /** 只序列化已归一化对象；调用者不得传入任意Map。 */
    public static String json(Manifest manifest) {
        try {
            // 显示字段不写入旧权限JSON，防止旧版本解析失败或旧摘要改变。
            var tree = JSON.valueToTree(normalize(manifest));
            for (var menu : tree.get("menus")) { ((com.fasterxml.jackson.databind.node.ObjectNode) menu).remove(List.of("label","position")); }
            return JSON.writeValueAsString(tree);
        }
        catch (Exception failure) { throw invalid(); }
    }
    /** 持久化内容摘要用于冲突识别，不包含认证凭据。 */
    public static String hash(Manifest manifest) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(manifest).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("JVM缺少SHA-256"); }
    }
    /** 展示元数据独立归一化，省略显示项对应真正的旧目录而不是伪造名称。 */
    public static String presentationJson(Manifest manifest) {
        try { return JSON.writeValueAsString(normalize(manifest).menus().stream().filter(n -> n.label()!=null)
                .map(n -> new MenuPresentation(n.code(),n.label(),n.position())).toList()); }
        catch (Exception failure) { throw invalid(); }
    }
    /** 显示摘要只用于不可变快照冲突，不作为业务判权。 */
    public static String presentationHash(Manifest manifest) { return digest(presentationJson(manifest)); }
    /** 读取显示快照时也重验引用、顺序和摘要，不信任损坏的数据库JSON。 */
    public static Manifest withPresentation(Manifest manifest, String json, String expectedHash) {
        if (expectedHash == null) {
            if (!"[]".equals(json)) { throw invalid(); }
            return manifest;
        }
        try {
            var values = JSON.readValue(json, MenuPresentation[].class);
            if (values.length == 0 || values.length > 100) { throw invalid(); }
            Map<String,MenuPresentation> entries = new HashMap<>();
            Set<String> codes = new HashSet<>(manifest.menus().stream().map(Menu::code).toList());
            for (var value : values) {
                if (value==null || !codes.contains(value.code()) || entries.put(value.code(),value)!=null) { throw invalid(); }
            }
            var decorated = normalize(new Manifest(manifest.schemaVersion(),manifest.application(),manifest.manifestVersion(),manifest.capabilities(),
                    manifest.menus().stream().map(n -> { var v=entries.get(n.code()); return v==null ? n : new Menu(n.code(),n.parent(),n.route(),n.anyOf(),v.label(),v.position()); }).toList()));
            if (!presentationHash(decorated).equals(expectedHash)) { throw invalid(); }
            return decorated;
        } catch (Exception failure) { throw invalid(); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("JVM缺少SHA-256"); }
    }
    private static GovernanceException invalid() { return new GovernanceException(INVALID_ARGUMENT); }
}
