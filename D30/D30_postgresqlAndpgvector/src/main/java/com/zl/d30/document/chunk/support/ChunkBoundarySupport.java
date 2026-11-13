package com.zl.d30.document.chunk.support;

import com.zl.d30.document.chunk.model.ChunkSource;
import com.zl.d30.document.chunk.model.TextBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk 边界与来源解析的公共支撑：固定长度与结构切分共用。
 *
 * <p>职责：语义边界切点选择（优先换行、再空格）、代理对安全保护、
 * 以及按全局偏移从结构块解析来源列表。</p>
 */
@Component
public class ChunkBoundarySupport {

    /**
     * 在硬切点附近选择更合理的边界：容差范围内优先换行，其次空格，都没有则硬切。
     *
     * @param text    规范全文
     * @param start   当前窗口起点
     * @param hardEnd 硬切点（不含）
     * @param tolerance 向前提取的最大容差
     * @return 调整后的切点（不含）
     */
    public int chooseEnd(String text, int start, int hardEnd, int tolerance) {
        int lower = Math.max(start + 1, hardEnd - tolerance);
        for (int i = hardEnd - 1; i >= lower; i--) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }
        for (int i = hardEnd - 1; i >= lower; i--) {
            if (text.charAt(i) == ' ') {
                return i + 1;
            }
        }
        return hardEnd;
    }

    /**
     * 代理对保护：切点若落在高代理与低代理之间，则向后包含完整的一对，
     * 避免产生半个字符。可能使块长比硬切点多 1 个 UTF-16 单元，属可接受边界。
     */
    public int roundEndForSurrogate(String text, int end) {
        if (end > 0 && end < text.length()) {
            char before = text.charAt(end - 1);
            char at = text.charAt(end);
            if (Character.isHighSurrogate(before) && Character.isLowSurrogate(at)) {
                return end + 1;
            }
        }
        return end;
    }

    /**
     * 起点代理对保护：起点若落在低代理上（一对的后半），则跳过该悬空低代理，
     * 该低代理已由上一块通过 {@link #roundEndForSurrogate} 完整包含。
     */
    public int roundStartForSurrogate(String text, int start) {
        if (start >= 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) {
            return start + 1;
        }
        return start;
    }

    /**
     * 解析与区间 [start,end) 相交的全部结构块来源，跨页/跨段全部保留。
     */
    public List<ChunkSource> resolveSources(List<TextBlock> blocks, int start, int end) {
        List<ChunkSource> sources = new ArrayList<>();
        for (TextBlock block : blocks) {
            if (block.getGlobalEnd() <= start) {
                continue;
            }
            if (block.getGlobalStart() >= end) {
                break;
            }
            int s = Math.max(start, block.getGlobalStart());
            int e = Math.min(end, block.getGlobalEnd());
            sources.add(new ChunkSource(block.getPageNumber(), block.getParagraphIndex(), s, e));
        }
        return sources;
    }
}
