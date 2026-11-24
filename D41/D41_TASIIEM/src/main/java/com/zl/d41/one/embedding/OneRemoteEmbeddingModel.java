package com.zl.d41.one.embedding;

import com.zl.d41.one.client.OneBailianEmbeddingClient;
import com.zl.d41.one.client.OneEmbeddingException;
import com.zl.d41.one.config.OneEmbeddingProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

import java.util.ArrayList;
import java.util.List;

/**
 * one_ 轨远程神经 Embedding 模型（阿里百炼 qwen3.7-text-embedding，1024 维）。
 *
 * <p>与 local 轨 {@code LocalHashingEmbeddingModel} 并列的第二个 {@code EmbeddingModel} Bean：
 * 本类<b>不加 {@code @Primary}</b>，所有注入点必须显式 {@code @Qualifier("oneEmbeddingModel")}；
 * local 轨 Bean 标注 {@code @Primary} 保证既有按类型注入仍解析到 local（H1 双轨契约）。</p>
 *
 * <p>关键行为：</p>
 * <ul>
 *   <li>{@link #dimensions()} 直接返回冻结配置值，<b>不触发网络</b>
 *       （接口默认实现会真实 embed 一次，启动期零调用红线）；</li>
 *   <li>批量按 maxBatch（P0 实测 16）分批，全局 index 连续；</li>
 *   <li>空白输入/超长输入拒绝（不静默截断）；零向量/维度不符/NaN 由客户端 fail-closed；</li>
 *   <li>输出未归一化（P0 G4 实测 L2≈0.0218），保持原样不自行归一化——
 *       COSINE_DISTANCE 对模长不敏感，与 local 轨 similarity=1−distance 口径一致。</li>
 * </ul>
 */
public class OneRemoteEmbeddingModel extends AbstractEmbeddingModel {

    private final OneEmbeddingProperties properties;
    private final OneBailianEmbeddingClient client;

    public OneRemoteEmbeddingModel(OneEmbeddingProperties properties, OneBailianEmbeddingClient client) {
        this.properties = properties;
        this.client = client;
        // 与 local 轨同一模式：父类维度缓存直接置为冻结值，dimensions() 零网络。
        this.embeddingDimensions.set(properties.getDimensions());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            throw new OneEmbeddingException(0, "BAD_REQUEST", false, "one_ embed 输入为空");
        }
        for (String text : instructions) {
            if (text == null || text.isBlank()) {
                throw new OneEmbeddingException(0, "BAD_REQUEST", false, "one_ embed 输入含空白文本，拒绝");
            }
            if (text.length() > properties.getMaxInputLength()) {
                throw new OneEmbeddingException(0, "INPUT_TOO_LONG", false,
                        "one_ embed 输入长度 " + text.length() + " 超过上限 " + properties.getMaxInputLength()
                                + "（UTF-16 字符，非 Token），拒绝静默截断");
            }
        }
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        int maxBatch = properties.getMaxBatch();
        for (int from = 0; from < instructions.size(); from += maxBatch) {
            int to = Math.min(from + maxBatch, instructions.size());
            List<float[]> vectors = client.embed(instructions.subList(from, to));
            for (int i = 0; i < vectors.size(); i++) {
                embeddings.add(new Embedding(vectors.get(i), from + i));
            }
        }
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel(properties.getModel());
        return new EmbeddingResponse(embeddings, metadata);
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    /** 冻结维度直返，零网络（启动期 PgVectorStore 维度断言依赖此行为）。 */
    @Override
    public int dimensions() {
        return properties.getDimensions();
    }
}