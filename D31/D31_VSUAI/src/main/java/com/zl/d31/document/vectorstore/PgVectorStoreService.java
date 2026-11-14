package com.zl.d31.document.vectorstore;

import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.vectorstore.dto.VectorMatchView;
import com.zl.d31.document.vectorstore.dto.VectorSearchRequest;
import com.zl.d31.document.vectorstore.dto.VectorWriteRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量存储应用服务：封装正式向量表的写入、相似度检索与统计。
 *
 * <p>职责边界：真实调用 {@link VectorStore}（内部经 EmbeddingModel 向量化并写入 pgvector），
 * 不做 RAG 问答、混合检索、重排序；空白文本拒绝，元数据保持 JSON 可序列化。</p>
 */
@Slf4j
@Service
public class PgVectorStoreService {

    private final VectorStore vectorStore;

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorStoreProperties properties;

    public PgVectorStoreService(VectorStore vectorStore,
                                @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                PgVectorStoreProperties properties) {
        this.vectorStore = vectorStore;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.properties = properties;
    }

    /**
     * 写入文本项。
     *
     * @return 实际写入条数
     */
    public int addItems(List<VectorWriteRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("写入项不能为空");
        }
        List<Document> documents = new ArrayList<>(items.size());
        for (VectorWriteRequest.Item item : items) {
            if (item == null || item.getText() == null || item.getText().isBlank()) {
                throw new IllegalArgumentException("写入文本不能为空或全空白");
            }
            Map<String, Object> metadata = sanitizeMetadata(item.getMetadata());
            documents.add(new Document(item.getText(), metadata));
        }
        vectorStore.add(documents);
        log.info("向量写入完成，数量={}，表={}", documents.size(), qualifiedTable());
        return documents.size();
    }

    /**
     * 写入 Chunk：把来源元数据一并持久化，保证可追溯。
     *
     * @return 实际写入条数
     */
    public int addChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("待写入 Chunk 列表为空");
        }
        List<Document> documents = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                throw new IllegalArgumentException("Chunk 内容为空，禁止写入无意义向量");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            putIfNotNull(metadata, "documentId", chunk.getDocumentId());
            putIfNotNull(metadata, "title", chunk.getTitle());
            if (chunk.getVersion() != null) {
                metadata.put("version", chunk.getVersion());
            }
            if (chunk.getStrategy() != null) {
                metadata.put("strategy", chunk.getStrategy().name());
            }
            metadata.put("chunkIndex", chunk.getChunkIndex());
            documents.add(new Document(chunk.getContent(), metadata));
        }
        vectorStore.add(documents);
        log.info("Chunk 向量写入完成，数量={}，表={}", documents.size(), qualifiedTable());
        return documents.size();
    }

    /**
     * 真实相似度检索：查询文本经 EmbeddingModel 向量化，按余弦距离排序。
     */
    public List<VectorMatchView> search(VectorSearchRequest request) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(request.getQuery())
                .topK(request.getTopK())
                .similarityThreshold(request.getThreshold())
                .build();
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        List<VectorMatchView> views = new ArrayList<>(results.size());
        for (Document document : results) {
            double score = document.getScore() == null ? 0.0 : document.getScore();
            views.add(new VectorMatchView(
                    document.getId(), document.getText(), score, document.getMetadata()));
        }
        log.info("相似度检索完成，query 长度={}，命中={}，topK={}",
                request.getQuery().length(), views.size(), request.getTopK());
        return views;
    }

    /**
     * 正式向量表当前行数。
     */
    public long count() {
        Long count = pgVectorJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTable(), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 全限定表名（带双引号，安全标识）。
     */
    public String qualifiedTable() {
        return "\"" + properties.getSchema() + "\".\"" + properties.getTable() + "\"";
    }

    /**
     * 元数据清洗：仅保留非空简单值，避免 JSON 序列化失败。
     */
    private Map<String, Object> sanitizeMetadata(Map<String, Object> source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source == null) {
            return metadata;
        }
        source.forEach((key, value) -> {
            if (key != null && value != null && isSimpleValue(value)) {
                metadata.put(key, value);
            }
        });
        return metadata;
    }

    private boolean isSimpleValue(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, String value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
