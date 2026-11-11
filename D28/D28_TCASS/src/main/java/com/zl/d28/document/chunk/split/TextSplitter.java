package com.zl.d28.document.chunk.split;

import com.zl.d28.document.chunk.model.Chunk;
import com.zl.d28.document.chunk.model.ChunkContext;
import com.zl.d28.document.chunk.model.ChunkStrategy;
import com.zl.d28.document.chunk.model.CleanedDocument;
import com.zl.d28.document.chunk.config.ChunkProperties;

import java.util.List;

/**
 * 文本切分器抽象：不同策略实现同一接口，可独立执行、独立替换。
 *
 * <p>切分器只依赖清洗结果与配置，不依赖 Embedding/VectorStore，
 * 保持组件独立可替换。</p>
 */
public interface TextSplitter {

    /** 该实现对应的切分策略。 */
    ChunkStrategy strategy();

    /**
     * 对清洗结果执行切分。
     *
     * @param document 清洗结果
     * @param properties 切分配置（已校验）
     * @param context 文档身份上下文
     * @return 有序 Chunk 列表；空输入返回空列表，不产生空 Chunk
     */
    List<Chunk> split(CleanedDocument document, ChunkProperties properties, ChunkContext context);
}
