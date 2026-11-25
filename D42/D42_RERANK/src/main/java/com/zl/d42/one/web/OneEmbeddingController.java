package com.zl.d42.one.web;

import com.zl.d42.common.Result;
import com.zl.d42.document.embedding.dto.CompatibilityCheckRequest;
import com.zl.d42.document.embedding.dto.CompatibilityDecisionView;
import com.zl.d42.document.embedding.dto.EmbeddingModelMetadataView;
import com.zl.d42.document.embedding.dto.EmbeddingViewMapper;
import com.zl.d42.document.embedding.model.CompatibilityDecision;
import com.zl.d42.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d42.one.client.OneBailianEmbeddingClient;
import com.zl.d42.one.config.OneEmbeddingProperties;
import com.zl.d42.one.service.OneIndexCompatibilityService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * one_ 轨 Embedding 观测端点：真实调用计数与 usage Token 累计（评测器/预算核对读取）。
 *
 * <p>仅暴露脱敏观测值（计数/维度/空间标识），绝不暴露密钥与地址全量。</p>
 */
@RestController
@RequestMapping("/api/v1/one/embeddings")
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneEmbeddingController {

    private final OneBailianEmbeddingClient client;
    private final OneEmbeddingProperties properties;
    private final OneIndexCompatibilityService compatibilityService;
    private final EmbeddingViewMapper mapper;

    public OneEmbeddingController(OneBailianEmbeddingClient client,
                                  OneEmbeddingProperties properties,
                                  OneIndexCompatibilityService compatibilityService,
                                  EmbeddingViewMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.compatibilityService = compatibilityService;
        this.mapper = mapper;
    }

    /** 真实调用与 Token 累计（LongAdder 线程安全；usage 来自 API 真实返回，非估算）。 */
    @GetMapping("/usage")
    public Result<Map<String, Object>> usage() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("model", properties.getModel());
        view.put("modelVersion", properties.getModelVersion());
        view.put("dimensions", properties.getDimensions());
        view.put("vectorSpaceId", properties.vectorSpaceId());
        view.put("calls", client.getCallCount());
        view.put("totalTokens", client.getTotalTokens());
        view.put("usageUnavailableCalls", client.getUsageUnavailableCalls());
        return Result.success(view);
    }

    /** one_ 当前模型与向量空间元数据（对齐 local /embeddings/model 语义）。 */
    @GetMapping("/model")
    public Result<EmbeddingModelMetadataView> model() {
        return Result.success(mapper.toMetadataView(compatibilityService.currentIndex()));
    }

    /**
     * one_ 索引兼容性检查：异维度 → DIMENSION_MISMATCH；
     * 同维度异空间（含 local 轨候选）→ MODEL_SPACE_MISMATCH，fail-closed。
     */
    @PostMapping("/index/compatibility")
    public Result<CompatibilityDecisionView> checkCompatibility(
            @Valid @RequestBody CompatibilityCheckRequest request) {
        EmbeddingModelMetadata candidate = mapper.toCandidate(request);
        CompatibilityDecision decision = compatibilityService.check(candidate);
        return Result.success(mapper.toDecisionView(
                decision, compatibilityService.currentIndex(), candidate));
    }
}