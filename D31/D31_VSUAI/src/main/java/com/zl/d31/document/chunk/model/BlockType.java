package com.zl.d31.document.chunk.model;

/**
 * 结构块类型：标题、普通段落、列表项。
 *
 * <p>用于结构切分中识别边界与标题归属；标题识别为启发式，
 * 不保证所有 PDF 都能准确还原标题层级。</p>
 */
public enum BlockType {

    /** 标题（章节/编号/Markdown 等启发式识别）。 */
    HEADING,

    /** 普通段落。 */
    PARAGRAPH,

    /** 列表项（项目符号或编号列表）。 */
    LIST_ITEM
}
