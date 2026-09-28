package com.lrj.authz.admin.governance;

import com.lrj.authz.core.SpiceDbProjectionGraph;
import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.domain.ProjectionModels.*;
import com.lrj.authz.governance.persistence.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

/** 显式单批进程入口，适合外部受控调度；不会自动启用应用或创建无限循环。 */
public final class ReliableProjectionCli {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ReliableProjectionCli.class);
    private ReliableProjectionCli() {}

    /** 进程预算30秒，超时终止仍由持久operation和图marker恢复未知结果。 */
    public static void main(String[] args) {
        if (args.length != 1) { LOG.error("PROJECTION_ARGUMENT_INVALID"); System.exit(3); }
        FutureTask<Integer> task = new FutureTask<>(() -> execute(args[0]));
        Thread.ofPlatform().daemon(true).name("governance-projection-batch").start(task);
        try { System.exit(task.get(30, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); task.cancel(true); LOG.error("PROJECTION_INTERRUPTED"); System.exit(3); }
        catch (ExecutionException | TimeoutException failure) { task.cancel(true); LOG.error("PROJECTION_RESULT_UNKNOWN"); System.exit(3); }
    }

    private static int execute(String file) {
        var p = GovernanceConfigurationFile.read(file);
        var partition = new Partition(p.getProperty("access.tenant"), p.getProperty("access.application"), p.getProperty("access.environment"));
        Kind kind = Kind.valueOf(p.getProperty("projection.kind"));
        String worker = UUID.randomUUID().toString();
        try (var runtime = GovernanceRuntime.open(GovernanceDatabase.from(p), false)) {
            var graph = new SpiceDbProjectionGraph(p.getProperty("graph.http"), p.getProperty("graph.key"), Duration.ofSeconds(3));
            Step result = runtime.reliableProjector(graph).step(partition, kind, worker);
            LOG.info("projection worker={} kind={} result={}", worker, kind, result);
            return result == Step.READY ? 0 : 2;
        }
    }
}
