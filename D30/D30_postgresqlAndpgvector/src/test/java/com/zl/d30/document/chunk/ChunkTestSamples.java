package com.zl.d30.document.chunk;

import com.zl.d30.document.chunk.config.ChunkProperties;
import com.zl.d30.document.model.DocumentPageContent;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试样本工厂：构建带身份元数据的真实页面对象与标准配置，供各切分测试复用。
 *
 * <p>所有样本均为真实内存对象，不使用 Mock 替代切分算法。</p>
 */
public final class ChunkTestSamples {

    public static final String DOCUMENT_ID = "doc-chunk-001";
    public static final String TITLE = "佳能相机操作手册";
    public static final String SOURCE = "manual";

    private ChunkTestSamples() {
    }

    /** 标准配置：size=200, overlap=50, min=20, max=400, tolerance=40。 */
    public static ChunkProperties properties() {
        ChunkProperties props = new ChunkProperties();
        props.setChunkSize(200);
        props.setChunkOverlap(50);
        props.setMinChunkSize(20);
        props.setMaxChunkSize(400);
        props.setBoundaryTolerance(40);
        props.setMergeShortBlocks(true);
        return props;
    }

    /** 单页内容。 */
    public static DocumentPageContent page(int pageNumber, String content) {
        return page(pageNumber, content, 1);
    }

    public static DocumentPageContent page(int pageNumber, String content, int version) {
        DocumentPageContent page = new DocumentPageContent();
        page.setDocumentId(DOCUMENT_ID);
        page.setSource(SOURCE);
        page.setTitle(TITLE);
        page.setVersion(version);
        page.setPageNumber(pageNumber);
        page.setContent(content);
        page.setContentLength(content == null ? 0 : content.length());
        page.setBlank(false);
        return page;
    }

    /** 多页内容（版本 1）。 */
    public static List<DocumentPageContent> pages(String... contents) {
        List<DocumentPageContent> list = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            list.add(page(i + 1, contents[i]));
        }
        return list;
    }

    /** 生成指定长度的连续英文单词串（空格分隔，便于边界识别）。 */
    public static String repeatedWords(int wordCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wordCount; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append("word").append(i);
        }
        return sb.toString();
    }

    /** 生成无断点的超长字符串（用于硬切与超长单句测试）。 */
    public static String unbroken(int length) {
        return "a".repeat(length);
    }
}
