package com.zl.d30.document.embedding.service;

import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.embedding.config.EmbeddingProperties;
import com.zl.d30.document.embedding.model.EmbeddingRecord;
import com.zl.d30.document.embedding.support.EmbeddingRetryExecutor;
import com.zl.d30.document.embedding.support.EmbeddingServiceException;
import com.zl.d30.document.embedding.support.VectorValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档向量化服务：把 Chunk 文本经真实 EmbeddingModel 转为带元数据的向量记录。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>空/空白文本拒绝，不产生无意义向量；</li>
 *   <li>超长文本拒绝（不静默截断、不丢来源），由调用方重新切分；</li>
 *   <li>调用经有限重试执行器，结果经向量校验器；</li>
 *   <li>批量嵌入并绑定 vectorSpaceId，保证可追溯。</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentEmbeddingService {

    private final EmbeddingModel embeddingModel;

    private final EmbeddingProperties properties;

    private final VectorValidator validator;

    private final EmbeddingRetryExecutor retryExecutor;

    public DocumentEmbeddingService(EmbeddingModel embeddingModel,
                                    EmbeddingProperties properties,
                                    VectorValidator validator,
                                    EmbeddingRetryExecutor retryExecutor) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.validator = validator;
        this.retryExecutor = retryExecutor;
    }

    /**
     * 批量嵌入 Chunk。
     *
     * @param chunks 已切分的 Chunk
     * @return 向量记录列表，顺序与输入一致
     */
    public List<EmbeddingRecord> embedChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw EmbeddingServiceException.nonRetryable("待嵌入 Chunk 列表为空");
        }
        List<EmbeddingRecord> records = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            records.add(embedOne(chunk));
        }
        log.info("批量嵌入完成，数量={}，维度={}，vectorSpaceId={}",
                records.size(), properties.getDimensions(), properties.vectorSpaceId());
        return records;
    }

    /**
     * 单条 Chunk 嵌入：前置校验 → 重试调用 → 结果校验 → 组装记录。
     */
    private EmbeddingRecord embedOne(Chunk chunk) {
        String content = chunk.getContent();
        if (content == null || content.isBlank()) {
            throw EmbeddingServiceException.nonRetryable(
                    "Chunk 内容为空或全空白，documentId=" + chunk.getDocumentId()
                            + "，chunkIndex=" + chunk.getChunkIndex());
        }
        if (content.length() > properties.getMaxInputLength()) {
            throw EmbeddingServiceException.nonRetryable(
                    "Chunk 长度 " + content.length() + " 超过上限 " + properties.getMaxInputLength()
                            + "（UTF-16 字符，非 Token），请重新切分，禁止静默截断");
        }

        float[] vector = retryExecutor.execute(() -> embeddingModel.embed(content));
        validator.validate(vector, properties.getDimensions());

        return new EmbeddingRecord(
                chunk.getDocumentId(),
                chunk.getTitle(),
                chunk.getVersion(),
                chunk.getStrategy() == null ? null : chunk.getStrategy().name(),
                chunk.getChunkIndex(),
                properties.getModel(),
                properties.getDimensions(),
                properties.vectorSpaceId(),
                vector);
    }
}
