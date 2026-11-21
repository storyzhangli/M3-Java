package com.zl.d38.hybrid.web;

import com.zl.d38.common.Result;
import com.zl.d38.hybrid.keyword.InvertedIndex;
import com.zl.d38.hybrid.keyword.KeywordIndexStats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关键词倒排索引运维端点（只读取统计；重建仅重建内存索引，零 DB 写入）。
 *
 * <p>rebuild 同时服务 C03 确定性取证：多次重建 {@code indexDigest} 必须一致。
 * 堆增量口径（H7）：{@code buildHeapDeltaBytes} 为构建前后 usedHeap 差值，粗测，
 * 正式口径取多次 rebuild 的中位数（P6 成本报告注明）。</p>
 */
@RestController
@RequestMapping("/api/v1/hybrid/keyword-index")
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class HybridKeywordIndexController {

    private final InvertedIndex invertedIndex;

    public HybridKeywordIndexController(@Qualifier("invertedIndex") InvertedIndex invertedIndex) {
        this.invertedIndex = invertedIndex;
    }

    /** 当前快照统计（chunk/term/posting 数、utf8 精确字节、指纹、摘要、构建耗时与堆差值）。 */
    @GetMapping("/stats")
    public Result<KeywordIndexStats> stats() {
        return Result.success(invertedIndex.stats());
    }

    /** 显式重建内存倒排（语料变更后的受控动作；返回新快照统计）。 */
    @PostMapping("/rebuild")
    public Result<KeywordIndexStats> rebuild() {
        return Result.success(invertedIndex.rebuild());
    }
}
