package com.zl.d28.document.chunk.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 清洗结果：规范全文 + 结构块列表 + 逐页清洗摘要。
 *
 * <p>三类对象分离的承载者：原始提取文本（来自页面）、清洗后规范文本
 * （{@link #joinedText}）、清洗来源元数据（{@link #blocks} 与
 * {@link #pages}）。清洗不丢失页面定位，每个块与页都保留来源。</p>
 */
@Data
public class CleanedDocument {

    /** 拼接后的规范全文（块间以单个 LF 连接）。 */
    private String joinedText = "";

    /** 有序结构块，含全局偏移与来源。 */
    private List<TextBlock> blocks = new ArrayList<>();

    /** 逐页清洗摘要。 */
    private List<CleanedPage> pages = new ArrayList<>();

    /** 全部页面清洗前总长度。 */
    private int originalLength;

    /** 规范全文 UTF-16 长度。 */
    private int cleanedLength;

    /** 清洗前是否不含任何有效字符（空/纯空白输入）。 */
    private boolean empty;
}
