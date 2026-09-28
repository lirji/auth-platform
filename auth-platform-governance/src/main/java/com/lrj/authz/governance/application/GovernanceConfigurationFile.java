package com.lrj.authz.governance.application;

import com.lrj.authz.governance.authentication.CasdoorAccessTokenVerifier;
import com.lrj.authz.governance.authentication.TokenAuthority;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** 唯一显式私密文件配置源；只有宿主启用治理路径时读取，没有动态配置/默认凭据。 */
public final class GovernanceConfigurationFile {
    private static final int MAX_FILE_BYTES = 65_536;
    private static final int MAX_CALLERS = 32;
    private GovernanceConfigurationFile() {}

    /** 凭据文件必须普通文件且 0600，失败只报固定配置错误，不回显文件内容。 */
    public static Properties read(String filename) {
        try {
            if (filename == null || filename.isBlank()) { throw invalid(); }
            Path path = Path.of(filename);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES
                    || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) { throw invalid(); }
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(path)) { properties.load(reader); }
            return properties;
        } catch (IOException | IllegalArgumentException failure) { throw invalid(); }
    }

    /** caller/app/environment/操作/用户发行方均来自部署配置，不能由请求选择。 */
    public static List<CallerService> callers(Properties properties) {
        try {
            int count = Integer.parseInt(properties.getProperty("service.count"));
            if (count < 1 || count > MAX_CALLERS) { throw invalid(); }
            List<CallerService> result = new ArrayList<>();
            for (int index = 1; index <= count; index++) {
                String prefix = "service." + index + ".";
                if (!"context.resolve".equals(properties.getProperty(prefix + "operation"))) { throw invalid(); }
                Properties token = new Properties();
                String userPrefix = prefix + "user.";
                for (String name : properties.stringPropertyNames()) {
                    if (name.startsWith(userPrefix)) { token.setProperty(name.substring(userPrefix.length()), properties.getProperty(name)); }
                }
                result.add(new CallerService(properties.getProperty(prefix + "id"), properties.getProperty(prefix + "application-id"),
                        properties.getProperty(prefix + "environment"), CallerService.parseHash(properties.getProperty(prefix + "credential-sha256")),
                        new CasdoorAccessTokenVerifier(TokenAuthority.from(token))));
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
    }

    private static GovernanceException invalid() { return new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT); }
}
