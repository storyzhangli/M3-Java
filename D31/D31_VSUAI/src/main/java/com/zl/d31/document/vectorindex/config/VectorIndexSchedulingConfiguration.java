package com.zl.d31.document.vectorindex.config;

import com.zl.d31.document.vectorindex.service.VectorReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 向量索引定时核对配置。
 *
 * <p>默认关闭（{@code app.vector-index.reconcile-enabled=false}），
 * 仅在显式开启时启用调度并按 Cron 执行真实补偿；{@link EnableScheduling}
 * 放在此配置上，避免影响其他模块的默认行为。</p>
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.vector-index", name = "reconcile-enabled", havingValue = "true")
public class VectorIndexSchedulingConfiguration {

    private final VectorReconciliationService reconciliationService;

    public VectorIndexSchedulingConfiguration(VectorReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * 定时核对并执行补偿（非 dryRun）。
     */
    @Scheduled(cron = "${app.vector-index.reconcile-cron}")
    public void scheduledReconcile() {
        log.info("开始执行定时向量核对");
        reconciliationService.reconcile(false);
    }
}
