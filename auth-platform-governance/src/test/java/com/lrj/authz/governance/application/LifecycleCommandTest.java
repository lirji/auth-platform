package com.lrj.authz.governance.application;

import com.lrj.authz.governance.cli.LifecycleCli;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 命令输入和操作范围必须在连接数据库前拒绝，避免伪造身份触及写入边界。 */
class LifecycleCommandTest {
    @Test void globalAndTenantAuthoritiesCannotBeInterchanged() {
        assertThatThrownBy(() -> command(UUID.randomUUID().toString(), LifecycleCommand.Operation.SUSPEND_PRINCIPAL, 1, "reason"))
                .isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> command(LifecycleCommand.GLOBAL_SCOPE, LifecycleCommand.Operation.SUSPEND_MEMBER, 1, "reason"))
                .isInstanceOf(GovernanceException.class);
    }
    @Test void requiresBoundedReasonVersionAndKnownOperation() {
        for (long version : new long[]{0, -1, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> command(LifecycleCommand.GLOBAL_SCOPE, LifecycleCommand.Operation.SUSPEND_PRINCIPAL, version, "reason"))
                    .isInstanceOf(GovernanceException.class);
        }
        for (String reason : new String[]{"", " ", "bad\nreason", "x".repeat(1001)}) {
            assertThatThrownBy(() -> command(LifecycleCommand.GLOBAL_SCOPE, LifecycleCommand.Operation.SUSPEND_PRINCIPAL, 1, reason))
                    .isInstanceOf(GovernanceException.class);
        }
        assertThatThrownBy(() -> LifecycleCommand.Operation.fromCli("resume-member")).isInstanceOf(GovernanceException.class);
    }
    @Test void cliRejectsCommandSuppliedOperatorBeforeConnecting() throws Exception {
        var config = Files.createTempFile("lifecycle-config", ".properties");
        var input = Files.createTempFile("lifecycle-command", ".properties");
        Files.writeString(config, "lifecycle.operator-ref=controlled-operator\nlifecycle.scope=GLOBAL\n");
        Files.writeString(input, "command.id=" + UUID.randomUUID() + "\ntarget.id=" + UUID.randomUUID()
                + "\nexpected.version=1\nreason=test\noperator.ref=forged-admin\n");
        for (var path : new java.nio.file.Path[]{config, input}) { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        var output = new StringWriter(); var error = new StringWriter();
        assertThat(LifecycleCli.run(new String[]{"suspend-principal", config.toString(), input.toString()}, new PrintWriter(output), new PrintWriter(error))).isEqualTo(2);
        assertThat(output.toString()).isEmpty();
        assertThat(error.toString()).contains("INVALID_ARGUMENT").doesNotContain("forged-admin");
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(LifecycleCli.run(new String[]{"suspend-principal", config.toString(), input.toString()}, new PrintWriter(output), new PrintWriter(error))).isEqualTo(2);
    }
    private static LifecycleCommand command(String scope, LifecycleCommand.Operation operation, long version, String reason) {
        return new LifecycleCommand(UUID.randomUUID().toString(), "test-operator", scope, operation, UUID.randomUUID().toString(), version, reason);
    }
}
