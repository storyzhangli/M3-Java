package com.zl.d29.document.embedding;

import com.zl.d29.document.embedding.support.EmbeddingServiceException;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 受控测试桩 EmbeddingModel（明确标注：测试替身，非真实接入的第二个模型）。
 *
 * <p>可配置前 N 次调用抛出可重试/不可重试异常，之后返回固定向量；
 * 用于验证重试执行器与服务层的失败处理，不用于声称真实模型能力。</p>
 */
public class StubEmbeddingModel extends AbstractEmbeddingModel {

    private final int dimensions;

    private final int failTimes;

    private final boolean retryable;

    private int callCount;

    public StubEmbeddingModel(int dimensions, int failTimes, boolean retryable) {
        this.dimensions = dimensions;
        this.failTimes = failTimes;
        this.retryable = retryable;
        this.embeddingDimensions.set(dimensions);
    }

    public int getCallCount() {
        return callCount;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        callCount++;
        if (callCount <= failTimes) {
            if (retryable) {
                throw EmbeddingServiceException.recoverable("受控临时故障 #" + callCount);
            }
            throw EmbeddingServiceException.nonRetryable("受控永久故障 #" + callCount);
        }
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(fixedVector(), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        callCount++;
        if (callCount <= failTimes) {
            if (retryable) {
                throw EmbeddingServiceException.recoverable("受控临时故障 #" + callCount);
            }
            throw EmbeddingServiceException.nonRetryable("受控永久故障 #" + callCount);
        }
        return fixedVector();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /** 固定的归一化向量：首维为 1，其余为 0。 */
    private float[] fixedVector() {
        float[] vector = new float[dimensions];
        vector[0] = 1.0f;
        return vector;
    }
}
