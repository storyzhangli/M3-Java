package com.zl.d41.document.sync.config;

import com.zl.d41.document.sync.dto.SyncRetryResultView;
import com.zl.d41.document.sync.service.SyncRetryRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * sync 自动重试调度配置（H2：{@code app.vector-index.sync-retry.enabled} 默认 false，
 * 无 matchIfMissing → 主 profile 不装配、不启动调度；手动 retry 端点不受本开关限制恒可用）。
 *
 * <p>{@code @EnableScheduling} 与 D39 VectorIndexSchedulingConfiguration 同型放本开关配置上，
 * 两个调度面（reconcile / sync-retry）各自独立开关、正交共存（任务书 P1.3）。</p>
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.vector-index.sync-retry", name = "enabled", havingValue = "true")
public class SyncRetrySchedulingConfiguration {

    private final SyncRetryRunner retryRunner;

    public SyncRetrySchedulingConfiguration(SyncRetryRunner retryRunner) {
        this.retryRunner = retryRunner;
    }

    /**
     * 周期扫描到期 FAILED 任务并重跑管线（fixedDelay 默认 60s，可配置）。
     */
    @Scheduled(fixedDelayString = "${app.vector-index.sync-retry.scan-interval-ms:60000}",
            initialDelayString = "${app.vector-index.sync-retry.initial-delay-ms:10000}")
    public void scheduledRetryDue() {
        List<SyncRetryResultView> results = retryRunner.retryDue();
        if (!results.isEmpty()) {
            log.info("sync 自动重试执行 {} 项", results.size());
        }
    }
}
