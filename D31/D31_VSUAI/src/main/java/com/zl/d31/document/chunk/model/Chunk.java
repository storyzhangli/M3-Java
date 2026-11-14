package com.zl.d31.document.chunk.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Chunk（切分块）：后续 Embedding 的输入准备单元，本迭代不与 Embedding 耦合。
 *
 * <p>保留任务书要求的来源元数据：documentId、title、version、chunkIndex、
 * content，以及策略、长度、全局偏移与来源列表。{@code chunkIndex} 从 0 起按
 * 发射顺序编号，在同一文档版本与策略内稳定有序；身份命名空间为
 * documentId + version + strategy + chunkIndex，跨文档不混淆。</p>
 */
@Data
public class Chunk {

    /** 原始知识文档业务标识。 */
    private String documentId;

    /** 原始文档标题。 */
    private String title;

    /** 原始文档版本。 */
    private Integer version;

    /** 切分策略。 */
    private ChunkStrategy strategy;

    /** 当前文档版本与策略内的 Chunk 索引，从 0 开始。 */
    private int chunkIndex;

    /** 当前 Chunk 文本。 */
    private String content;

    /** 文本 UTF-16 code unit 长度。 */
    private int length;

    /** Unicode 码点数（与 UTF-16 长度对照，补充字符会不同）。 */
    private int codePointCount;

    /** 在规范全文中的起始偏移（含），UTF-16。 */
    private int globalStart;

    /** 在规范全文中的结束偏移（不含），UTF-16。 */
    private int globalEnd;

    /** 来源列表（跨页/跨段时为多个，不丢弃非首个来源）。 */
    private List<ChunkSource> sources = new ArrayList<>();

    public Chunk() {
    }

    /** 用于发射 Chunk 的便捷构造器，长度与码点数由内容现算。 */
    public Chunk(String documentId, String title, Integer version, ChunkStrategy strategy,
                 int chunkIndex, String content, int globalStart, int globalEnd,
                 List<ChunkSource> sources) {
        this.documentId = documentId;
        this.title = title;
        this.version = version;
        this.strategy = strategy;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.length = content.length();
        this.codePointCount = content.codePointCount(0, content.length());
        this.globalStart = globalStart;
        this.globalEnd = globalEnd;
        this.sources = sources != null ? sources : new ArrayList<>();
    }

    /** 去重且有序的来源页码列表。 */
    public List<Integer> getPageNumbers() {
        return sources.stream()
                .map(ChunkSource::getPageNumber)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** 首个来源页码；无可靠页码时为 {@code null}。 */
    public Integer getFirstPageNumber() {
        return getPageNumbers().stream().findFirst().orElse(null);
    }

    /** 末个来源页码；无可靠页码时为 {@code null}。 */
    public Integer getLastPageNumber() {
        List<Integer> pages = getPageNumbers();
        return pages.isEmpty() ? null : pages.get(pages.size() - 1);
    }

    /** 去重且有序的来源段落索引列表。 */
    public List<Integer> getParagraphIndices() {
        return sources.stream()
                .map(ChunkSource::getParagraphIndex)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** 是否具备页码定位能力。 */
    public boolean hasPageLocation() {
        return sources.stream().anyMatch(s -> s.getPageNumber() != null);
    }

    /** 是否具备段落定位能力。 */
    public boolean hasParagraphLocation() {
        return sources.stream().anyMatch(s -> s.getParagraphIndex() != null);
    }

    /** 是否跨越多个物理页。 */
    public boolean isCrossPage() {
        return getPageNumbers().size() > 1;
    }
}
