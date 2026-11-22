package com.zl.d39.one.index.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * one_ 轨直接向量索引请求（结构对齐 local 轨 VectorIndexRequest，独立 DTO 不改既有契约）。
 *
 * <p>已知边界（D37 教训继承）：ChunkPayload 无 page/section 承载字段，
 * 直接入库链路 PG 侧 page=null，如实记录不伪造元数据。</p>
 */
@Data
public class OneVectorIndexRequest {

    @NotBlank(message = "documentId 不能为空")
    private String documentId;

    /** 文档版本；为空时默认 1。 */
    private Integer version;

    /** 切分策略；为空时使用默认策略。 */
    private String strategy;

    /** 文档标题（可选，写入元数据）。 */
    private String title;

    @NotEmpty(message = "chunks 不能为空")
    @Valid
    private List<ChunkPayload> chunks;

    @Data
    public static class ChunkPayload {

        /** Chunk 顺序，从 0 开始。 */
        private int chunkIndex;

        @NotBlank(message = "chunk 内容不能为空")
        private String content;

        private int globalStart;

        private int globalEnd;
    }
}