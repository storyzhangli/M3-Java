package com.zl.d29.document.chunk.split;

import com.zl.d29.document.chunk.config.ChunkProperties;
import com.zl.d29.document.chunk.model.Chunk;
import com.zl.d29.document.chunk.model.ChunkContext;
import com.zl.d29.document.chunk.model.ChunkSource;
import com.zl.d29.document.chunk.model.ChunkStrategy;
import com.zl.d29.document.chunk.model.CleanedDocument;
import com.zl.d29.document.chunk.support.ChunkBoundarySupport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度切分器：以硬游标按步长 {@code step = chunkSize - overlap} 推进，
 * 保证每次都有确定的前进；在硬切点附近按容差选择换行/空格边界，
 * 并通过无间隙钳制保证位置不漏覆盖。
 *
 * <p>语义边界调整会让相邻块的实际重叠落在 {@code [0, overlap]} 区间，
 * 仅当切点正好落在硬切点时才等于配置值；overlap=0 时严格不重叠。</p>
 */
@Component
public class FixedSizeSplitter implements TextSplitter {

    private final ChunkBoundarySupport support;

    public FixedSizeSplitter(ChunkBoundarySupport support) {
        this.support = support;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.FIXED_SIZE;
    }

    @Override
    public List<Chunk> split(CleanedDocument document, ChunkProperties props, ChunkContext context) {
        String text = document.getJoinedText();
        int n = text.length();
        List<Chunk> chunks = new ArrayList<>();
        if (n == 0) {
            return chunks;
        }

        int size = props.getChunkSize();
        int overlap = props.getChunkOverlap();
        int min = props.getMinChunkSize();
        int step = size - overlap;
        int tolerance = props.getBoundaryTolerance();

        int hardStart = 0;
        int chunkIndex = 0;

        while (hardStart < n) {
            int hardEnd = Math.min(hardStart + size, n);
            int contentEnd = support.chooseEnd(text, hardStart, hardEnd, tolerance);

            // 无间隙：至少覆盖到下一个硬起点，避免出现漏覆盖位置。
            int noGapEnd = Math.min(hardStart + step, n);
            contentEnd = Math.max(contentEnd, noGapEnd);
            contentEnd = Math.min(contentEnd, hardEnd);
            contentEnd = Math.max(contentEnd, hardStart + 1);
            contentEnd = support.roundEndForSurrogate(text, contentEnd);

            int contentStart = support.roundStartForSurrogate(text, hardStart);
            if (contentStart >= contentEnd) {
                contentStart = hardStart;
            }

            boolean isLast = contentEnd >= n;
            if (isLast && !chunks.isEmpty() && (contentEnd - contentStart) < min) {
                mergeIntoPrevious(chunks, text, document, contentEnd, support);
                break;
            }

            String content = text.substring(contentStart, contentEnd);
            List<ChunkSource> sources = support.resolveSources(
                    document.getBlocks(), contentStart, contentEnd);
            chunks.add(new Chunk(context.documentId(), context.title(), context.version(),
                    ChunkStrategy.FIXED_SIZE, chunkIndex++, content, contentStart, contentEnd, sources));

            if (isLast) {
                break;
            }
            hardStart += step;
        }

        return chunks;
    }

    private void mergeIntoPrevious(List<Chunk> chunks, String text, CleanedDocument document,
                                   int mergedEnd, ChunkBoundarySupport support) {
        Chunk previous = chunks.get(chunks.size() - 1);
        int mergedStart = previous.getGlobalStart();
        String mergedContent = text.substring(mergedStart, mergedEnd);
        List<ChunkSource> sources = support.resolveSources(
                document.getBlocks(), mergedStart, mergedEnd);
        previous.setContent(mergedContent);
        previous.setLength(mergedContent.length());
        previous.setCodePointCount(mergedContent.codePointCount(0, mergedContent.length()));
        previous.setGlobalEnd(mergedEnd);
        previous.setSources(sources);
    }
}
