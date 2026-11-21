package oneeval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冻结语料加载与逻辑 Chunk 映射。
 * 元数据只来自真实冻结语料，不补造任何字段。
 */
public final class Corpus {

    /** 单个逻辑片段（语料原始 chunkId，非向量库 UUID）。 */
    public record Chunk(String logicalChunkId, String documentId, String title,
                        String section, Integer page, String content) {
    }

    private final List<Chunk> chunks;
    private final Map<String, Chunk> byLogicalId;
    /** 内容+documentId → 逻辑片段：用于把 PGVECTOR 的 UUID 主键映射回标注体系。 */
    private final Map<String, Chunk> byContentAndDoc;

    private Corpus(List<Chunk> chunks) {
        this.chunks = List.copyOf(chunks);
        Map<String, Chunk> byId = new LinkedHashMap<>();
        Map<String, Chunk> byContent = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            byId.put(c.logicalChunkId(), c);
            String key = contentKey(c.documentId(), c.content());
            if (byContent.putIfAbsent(key, c) != null) {
                throw new IllegalStateException("语料内容重复，映射歧义: " + key);
            }
        }
        this.byLogicalId = Map.copyOf(byId);
        this.byContentAndDoc = Map.copyOf(byContent);
    }

    public static Corpus load(Path path, ObjectMapper mapper) throws IOException {
        JsonNode root = mapper.readTree(path.toFile());
        if (!root.isArray()) {
            throw new IllegalStateException("语料必须为文档数组");
        }
        List<Chunk> chunks = new ArrayList<>();
        for (JsonNode doc : root) {
            String documentId = doc.path("documentId").asText(null);
            String title = doc.path("title").asText(null);
            if (documentId == null || title == null) {
                throw new IllegalStateException("语料文档缺少 documentId/title");
            }
            for (JsonNode ch : doc.path("chunks")) {
                String logicalId = ch.path("chunkId").asText(null);
                String excerpt = ch.path("excerpt").asText(null);
                if (logicalId == null || excerpt == null || excerpt.isBlank()) {
                    throw new IllegalStateException("语料片段缺少 chunkId/excerpt");
                }
                Integer page = ch.hasNonNull("page") ? ch.get("page").asInt() : null;
                chunks.add(new Chunk(logicalId, documentId, title,
                        ch.path("section").asText(null), page, excerpt));
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("语料为空");
        }
        return new Corpus(chunks);
    }

    public static String contentKey(String documentId, String content) {
        return documentId + "\u0000" + content;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public Chunk byLogicalId(String id) {
        return byLogicalId.get(id);
    }

    /** UUID/内容映射：精确内容 + documentId 匹配；找不到返回 null（调用方标不可判）。 */
    public Chunk mapHit(String documentId, String content) {
        return byContentAndDoc.get(contentKey(documentId, content));
    }

    public List<String> documentIds() {
        return chunks.stream().map(Chunk::documentId).distinct().toList();
    }
}
