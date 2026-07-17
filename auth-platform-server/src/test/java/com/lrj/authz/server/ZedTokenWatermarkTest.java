package com.lrj.authz.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ZedTokenWatermarkTest {

    @Test
    void ignoresBlankAndPublishesNonBlankToken() {
        ZedTokenWatermark watermark = new ZedTokenWatermark();
        assertThat(watermark.latest()).isNull();
        watermark.advance(null);
        watermark.advance("");
        watermark.advance("   ");
        assertThat(watermark.latest()).isNull();

        watermark.advance("zed-1");
        watermark.advance(" ");
        assertThat(watermark.latest()).isEqualTo("zed-1");
    }

    @Test
    void concurrentAdvancePublishesACompleteSubmittedToken() throws Exception {
        ZedTokenWatermark watermark = new ZedTokenWatermark();
        Set<String> submitted = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> "zed-" + i).collect(java.util.stream.Collectors.toSet());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (String token : submitted) {
                futures.add(pool.submit(() -> {
                    start.await();
                    watermark.advance(token);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(watermark.latest()).isIn(submitted);
        // TODO(issue-S02): 不断言该值代表 SpiceDB 因果上最新 revision。
    }
}
