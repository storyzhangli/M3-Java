package com.zl.d31.document.vectorindex.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 直接向量索引请求：对调用方给出的 Chunk 载荷执行幂等入库。
 *
 * <p>不依赖文档原文表，便于在未完成原文表对齐时，仍可对真实 PG 验证全链路。</p>
 */
@Data
public class VectorIndexRequest {

    /** 文档业务标识。 */
    @NotBlank(message = "documentId 不能为空")
    private String documentId;

    /** 文档版本；为空时默认 1。 */
    private Integer version;

    /** 切分策略；为空时使用默认策略。 */
    private String strategy;

    /** 文档标题（可选，写入元数据）。 */
    private String title;

    /** Chunk 载荷列表。 */
    @NotEmpty(message = "chunks 不能为空")
    @Valid
    private List<ChunkPayload> chunks;

    /**
     * 单个 Chunk 载荷。
     */
    @Data
    public static class ChunkPayload {

        /** Chunk 顺序，从 0 开始。 */
        private int chunkIndex;

        /** Chunk 文本。 */
        @NotBlank(message = "chunk 内容不能为空")
        private String content;

        /** 全局起始偏移（可选）。 */
        private int globalStart;

        /** 全局结束偏移（可选）。 */
        private int globalEnd;
    }
}
