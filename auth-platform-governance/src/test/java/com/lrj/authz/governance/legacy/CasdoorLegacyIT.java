package com.lrj.authz.governance.legacy;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.application.GovernanceException;
import com.lrj.authz.governance.authentication.CasdoorAccessTokenVerifier;
import com.lrj.authz.governance.authentication.TokenAuthority;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/** 显式 legacy profile 才读取原发行方的本任务夹具；CI 不需要共享旧 IdP。 */
class CasdoorLegacyIT {
    @Test
    void refusesVerifiedLegacyVersionWhoseIdAndAccessTokensAreAliases() throws Exception {
        String directory = System.getenv("GOVERNANCE_LEGACY_FIXTURE");
        if (directory == null) {
            throw new IllegalStateException("需要本任务旧版夹具");
        }
        var json = new ObjectMapper();
        var fixture = json.readTree(privateText(Path.of(directory, "casdoor.json")));
        var tokens = json.readTree(privateText(Path.of(directory, "tokens.json")));
        String client = fixture.path("clients").path("management").path("name").asText();
        String secret = fixture.path("clients").path("management").path("secret").asText();
        assertThat(fixture.path("organization").asText()).matches("gov-p1-[a-f0-9]{12}");
        var request =
                (java.net.HttpURLConnection)
                        URI.create("http://localhost:8000/api/get-version-info")
                                .toURL()
                                .openConnection();
        request.setConnectTimeout(2_000);
        request.setReadTimeout(2_000);
        request.setInstanceFollowRedirects(false);
        request.setRequestProperty(
                "Authorization",
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (client + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        try (var body = request.getInputStream()) {
            assertThat(json.readTree(body).path("data").path("version").asText())
                    .isEqualTo("v4.3.0");
        } finally {
            request.disconnect();
        }
        assertThat(
                        tokens.path("management")
                                .path("access_token")
                                .asText()
                                .equals(tokens.path("management").path("id_token").asText()))
                .isTrue();
        TokenAuthority authority =
                new TokenAuthority(
                        "http://localhost:8000",
                        URI.create("http://localhost:8000/.well-known/jwks"),
                        client,
                        client,
                        secret,
                        client,
                        secret,
                        2_000,
                        2_000,
                        2);
        assertThatThrownBy(() -> new CasdoorAccessTokenVerifier(authority))
                .isInstanceOfSatisfying(
                        GovernanceException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(
                                                GovernanceException.Code.DEPENDENCY_UNAVAILABLE));
    }

    private static String privateText(Path file) throws Exception {
        assertThat(Files.isSymbolicLink(file)).isFalse();
        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        return Files.readString(file);
    }
}
