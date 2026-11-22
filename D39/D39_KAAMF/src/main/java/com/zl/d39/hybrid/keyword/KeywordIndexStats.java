package com.zl.d39.hybrid.keyword;

/**
 * 倒排索引统计（record）。口径（H7，任务书 P1.4）：
 *
 * <ul>
 *   <li>{@code indexUtf8Bytes}：postings 序列化（term=chunkId:tf,...，字典序、LF 连接）的
 *       UTF-8 字节数——<b>精确</b>，不含语料正文与 JVM 对象头；</li>
 *   <li>{@code buildHeapDeltaBytes}：构建前后 {@code Runtime.totalMemory()-freeMemory()} 差值——
 *       <b>粗测口径</b>，GC 噪声不可消除；正式口径取 3 次构建中位数（C15）；</li>
 *   <li>{@code indexDigest}：postings+docLen 规范序列化的 SHA-256，两次构建一致即
 *       确定性构建成立（C03）。</li>
 * </ul>
 */
public record KeywordIndexStats(
        int chunkCount,
        int termCount,
        int postingCount,
        double avgDocumentLength,
        long indexUtf8Bytes,
        String collectionFingerprint,
        String indexDigest,
        double buildMs,
        long buildHeapDeltaBytes,
        String builtAt) {
}
