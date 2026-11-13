package com.zl.d30.document.embedding.local;

import com.zl.d30.document.embedding.config.EmbeddingProperties;
import com.zl.d30.document.embedding.local.FeatureHasher;
import com.zl.d30.document.embedding.local.TextFeatureExtractor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地哈希词面向量模型：当前环境真实可调用的 EmbeddingModel。
 *
 * <p>纯 JVM 计算，零网络、零密钥、零费用；输出固定维度的 {@code float[]}，
 * 并做 L2 归一化。算法为带符号特征哈希（signed feature hashing）：
 * 特征 → 桶下标 + 符号位 → 累加 → 归一化。结果确定、可复现。</p>
 *
 * <p>诚实定位：这是词汇/字面向量（lexical），不是 BGE-M3 等神经语义模型；
 * 零字面重叠的同义句相似度趋近 0。</p>
 */
public class LocalHashingEmbeddingModel extends AbstractEmbeddingModel {

    private final int dimensions;

    private final String modelName;

    public LocalHashingEmbeddingModel(EmbeddingProperties properties) {
        this.dimensions = properties.getDimensions();
        this.modelName = properties.getModel();
        // 让父类的维度缓存与实际维度一致，dimensions() 直接返回该值。
        this.embeddingDimensions.set(dimensions);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(embedSingle(instructions.get(i)), i));
        }
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel(modelName);
        return new EmbeddingResponse(embeddings, metadata);
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * 单条文本向量化：特征哈希累加后 L2 归一化。
     */
    private float[] embedSingle(String text) {
        float[] vector = new float[dimensions];
        for (String feature : TextFeatureExtractor.extract(text)) {
            int bucket = FeatureHasher.bucket(feature, dimensions);
            vector[bucket] += FeatureHasher.sign(feature);
        }
        l2Normalize(vector);
        return vector;
    }

    /**
     * L2 归一化：使向量模长为 1，便于直接用点积计算余弦相似度。
     */
    private void l2Normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0.0) {
            // 无特征（空文本）时保持零向量；空文本在服务层会被提前拒绝。
            return;
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
