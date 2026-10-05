package com.lrj.authz.governance.identity.authentication;

import static com.lrj.authz.governance.shared.application.GovernanceException.Code.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.governance.identity.application.BootstrapCommand;
import com.lrj.authz.governance.runtime.persistence.GovernanceDatabase;
import com.lrj.authz.governance.runtime.persistence.GovernanceRuntime;
import com.lrj.authz.governance.shared.application.GovernanceException;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

/** 必须显式指定私密本机夹具；签发、验签和 PostgreSQL 身份绑定走真实边界。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CasdoorIdentityIT {
    private JsonNode fixture;
    private JsonNode tokens;
    private JsonNode operations;
    private String issuer;
    private GovernanceRuntime runtime;
    private JdbcTemplate jdbc;
    private CasdoorAccessTokenVerifier verifier;
    private AuthenticatedIdentityReader reader;
    private BootstrapCommand binding;

    @BeforeAll
    void connectToExplicitIsolatedAuthorities() throws Exception {
        Path directory = Path.of(required("GOVERNANCE_IDENTITY_FIXTURE")).toAbsolutePath();
        assertThat(Files.isSymbolicLink(directory)).isFalse();
        var json = new ObjectMapper();
        fixture = json.readTree(privateText(directory.resolve("casdoor.json")));
        tokens = json.readTree(privateText(directory.resolve("tokens.json")));
        operations = json.readTree(privateText(directory.resolve("management-client.json")));
        issuer = "http://localhost:18090";
        assertThat(
                        json.readTree(Files.readString(directory.resolve("runtime-result.json")))
                                .path("version")
                                .asText())
                .isEqualTo("v4.11.0");
        assertThat(fixture.path("organization").asText()).matches("gov-p1-[a-f0-9]{12}");
        Properties properties = new Properties();
        properties.load(
                new java.io.StringReader(privateText(Path.of(required("GOVERNANCE_TEST_CONFIG")))));
        assertThat(properties.getProperty("jdbc.url"))
                .matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+");
        GovernanceDatabase database = GovernanceDatabase.from(properties);
        runtime = GovernanceRuntime.open(database, true);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                database.jdbcUrl(), database.username(), database.password()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user",
                                Boolean.class))
                .isFalse();
        verifier =
                new CasdoorAccessTokenVerifier(
                        authority("management", issuer, client("management")));
        reader = new AuthenticatedIdentityReader(verifier, runtime.identity());
        binding =
                new BootstrapCommand(
                        id("command"),
                        "casdoor-it-" + fixture.path("organization").asText(),
                        id("tenant"),
                        "casdoor-it-" + fixture.path("organization").asText(),
                        id("principal"),
                        issuer,
                        fixture.path("users").path("internal").path("id").asText(),
                        id("membership"),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        null,
                        "casdoor-p1-isolated",
                        fixture.path("organization").asText(),
                        "internal");
        runtime.identity().bootstrapEmployee(binding);
    }

    @AfterAll
    void closeOwnPool() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void actualAccessTokenBindsExactIdentityAndReadsCurrentMembership() {
        assertThat(verifier.verify(token("management", "access_token")).subject())
                .isEqualTo(binding.subject());
        assertThat(reader.principal(token("management", "access_token")).id())
                .isEqualTo(binding.principalId());
        assertThat(reader.memberships(token("management", "access_token")))
                .extracting("id")
                .contains(binding.membershipId());
    }

    @Test
    void threeTokenFormatsSeparateIdAndAccessAndRejectIdPurpose() {
        for (String purpose : new String[] {"management", "standard", "full"}) {
            var check = new CasdoorAccessTokenVerifier(authority(purpose, issuer, client(purpose)));
            assertThat(token(purpose, "access_token").equals(token(purpose, "id_token"))).isFalse();
            assertThat(check.verify(token(purpose, "access_token")).subject())
                    .isEqualTo(binding.subject());
            expect(INVALID_CREDENTIAL, () -> check.verify(token(purpose, "id_token")));
        }
    }

    @Test
    void actualExpiredOtherClientIssuerAudienceAndTamperedSignatureAreRejected() {
        var expired =
                new CasdoorAccessTokenVerifier(authority("expired", issuer, client("expired")));
        expect(INVALID_CREDENTIAL, () -> expired.verify(token("expired", "access_token")));
        expect(INVALID_CREDENTIAL, () -> verifier.verify(token("business", "access_token")));
        var wrongAudience =
                new CasdoorAccessTokenVerifier(
                        authority("management", issuer, "unregistered-audience"));
        expect(INVALID_CREDENTIAL, () -> wrongAudience.verify(token("management", "access_token")));
        var wrongIssuer =
                new CasdoorAccessTokenVerifier(
                        authority("management", "http://127.0.0.1:18090", client("management")));
        expect(INVALID_CREDENTIAL, () -> wrongIssuer.verify(token("management", "access_token")));
        String[] parts = token("management", "access_token").split("\\.");
        String damaged =
                parts[0]
                        + "."
                        + parts[1]
                        + "."
                        + (parts[2].charAt(0) == 'A' ? "B" : "A")
                        + parts[2].substring(1);
        expect(INVALID_CREDENTIAL, () -> verifier.verify(damaged));
    }

    @Test
    void verifiedUnboundExternalUserDoesNotCreatePrincipalOrMembership() {
        var external =
                new AuthenticatedIdentityReader(
                        new CasdoorAccessTokenVerifier(
                                authority("external", issuer, client("external"))),
                        runtime.identity());
        int identitiesBefore =
                jdbc.queryForObject(
                        "SELECT count(*) FROM auth_governance.login_identity", Integer.class);
        expect(IDENTITY_NOT_BOUND, () -> external.memberships(token("external", "access_token")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.login_identity",
                                Integer.class))
                .isEqualTo(identitiesBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_governance.login_identity WHERE issuer=? AND subject=?",
                                Integer.class,
                                issuer,
                                fixture.path("users").path("external").path("id").asText()))
                .isZero();
    }

    @Test
    void stillValidTokenCannotKeepSuspendedMembershipVisible() {
        String suffix = UUID.randomUUID().toString();
        BootstrapCommand extra =
                new BootstrapCommand(
                        suffix,
                        binding.operatorRef(),
                        UUID.randomUUID().toString(),
                        "status-it-" + suffix,
                        binding.principalId(),
                        issuer,
                        binding.subject(),
                        UUID.randomUUID().toString(),
                        binding.validFrom(),
                        null,
                        "casdoor-p1-isolated",
                        suffix,
                        "status");
        runtime.identity().bootstrapEmployee(extra);
        assertThat(reader.memberships(token("management", "access_token")))
                .extracting("id")
                .contains(extra.membershipId());
        assertThat(
                        jdbc.update(
                                "UPDATE auth_governance.membership SET status='SUSPENDED', version=version+1 WHERE id=? AND version=1",
                                extra.membershipId()))
                .isEqualTo(1);
        assertThat(verifier.verify(token("management", "access_token")).subject())
                .isEqualTo(binding.subject());
        assertThat(reader.memberships(token("management", "access_token")))
                .extracting("id")
                .doesNotContain(extra.membershipId());
    }

    private TokenAuthority authority(String purpose, String configuredIssuer, String audience) {
        return new TokenAuthority(
                configuredIssuer,
                URI.create(issuer + "/.well-known/jwks"),
                audience,
                client(purpose),
                fixture.path("clients").path(purpose).path("secret").asText(),
                operations.path("client_id").asText(),
                operations.path("client_secret").asText(),
                2_000,
                2_000,
                4);
    }

    private String client(String purpose) {
        return fixture.path("clients").path(purpose).path("name").asText();
    }

    private String token(String purpose, String field) {
        return tokens.path(purpose).path(field).asText();
    }

    private String id(String purpose) {
        return UUID.nameUUIDFromBytes(
                        (fixture.path("organization").asText() + ":" + purpose)
                                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("隔离测试配置缺失: " + name);
        }
        return value;
    }

    private static String privateText(Path path) throws Exception {
        assertThat(Files.isSymbolicLink(path)).isFalse();
        assertThat(Files.getPosixFilePermissions(path))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        return Files.readString(path);
    }

    private static void expect(
            GovernanceException.Code code,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work)
                .isInstanceOfSatisfying(
                        GovernanceException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }
}
