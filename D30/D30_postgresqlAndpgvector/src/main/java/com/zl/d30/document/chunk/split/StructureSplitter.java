package com.zl.d30.document.chunk.split;

import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.chunk.model.ChunkContext;
import com.zl.d30.document.chunk.model.ChunkSource;
import com.zl.d30.document.chunk.model.ChunkStrategy;
import com.zl.d30.document.chunk.model.CleanedDocument;
import com.zl.d30.document.chunk.config.ChunkProperties;
import com.zl.d30.document.chunk.model.TextBlock;
import com.zl.d30.document.chunk.split.TextSplitter;
import com.zl.d30.document.chunk.support.ChunkBoundarySupport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按标题/段落结构切分器：尽量保持完整段落语义。
 *
 * <p>顺序累积结构块，标题开启新 Chunk 且标题与正文同块；连续短段落在最大
 * 长度内合并；单块超长时按边界保护二次切分（内部不重叠）；极短最后一段
 * 保留不丢弃。无明确标题时降级为段落 + 页面边界。</p>
 */
@Component
public class StructureSplitter implements TextSplitter {

    private final ChunkBoundarySupport support;

    public StructureSplitter(ChunkBoundarySupport support) {
        this.support = support;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.STRUCTURE;
    }

    @Override
    public List<Chunk> split(CleanedDocument document, ChunkProperties props, ChunkContext context) {
        List<Chunk> chunks = new ArrayList<>();
        if (document.isEmpty()) {
            return chunks;
        }
        String text = document.getJoinedText();
        int max = props.getMaxChunkSize();
        int tolerance = props.getBoundaryTolerance();

        List<TextBlock> current = new ArrayList<>();
        int currentLength = 0;

        for (TextBlock block : document.getBlocks()) {
            if (block.getLength() > max) {
                flush(current, context, text, document, chunks);
                splitOversized(block, context, text, document, props, max, tolerance, chunks);
                current = new ArrayList<>();
                currentLength = 0;
                continue;
            }

            if (!current.isEmpty() && block.getType() == com.zl.d30.document.chunk.model.BlockType.HEADING) {
                flush(current, context, text, document, chunks);
                current = new ArrayList<>();
                currentLength = 0;
            }

            int added = current.isEmpty() ? block.getLength() : currentLength + 1 + block.getLength();
            if (!current.isEmpty() && added > max) {
                flush(current, context, text, document, chunks);
                current = new ArrayList<>();
                currentLength = 0;
            }

            current.add(block);
            currentLength = current.isEmpty() ? block.getLength() : currentLength + 1 + block.getLength();
        }

        flush(current, context, text, document, chunks);

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }
        return chunks;
    }

    private void flush(List<TextBlock> blocks, ChunkContext context, String text,
                       CleanedDocument document, List<Chunk> chunks) {
        if (blocks.isEmpty()) {
            return;
        }
        int start = blocks.get(0).getGlobalStart();
        int end = blocks.get(blocks.size() - 1).getGlobalEnd();
        // 非末尾块包含其后的分隔换行，避免该字符在覆盖统计中漏覆盖。
        if (end < text.length() && text.charAt(end) == '\n') {
            end = end + 1;
        }

        List<ChunkSource> sources = support.resolveSources(document.getBlocks(), start, end);
        Chunk chunk = new Chunk(context.documentId(), context.title(), context.version(),
                ChunkStrategy.STRUCTURE, chunks.size(), text.substring(start, end),
                start, end, sources);
        chunks.add(chunk);
    }

    private void splitOversized(TextBlock block, ChunkContext context, String text,
                                CleanedDocument document, ChunkProperties props,
                                int max, int tolerance, List<Chunk> chunks) {
        int pos = block.getGlobalStart();
        int blockEnd = block.getGlobalEnd();
        while (pos < blockEnd) {
            int contentStart = support.roundStartForSurrogate(text, pos);
            int hardEnd = Math.min(pos + max, blockEnd);
            int end = support.chooseEnd(text, pos, hardEnd, tolerance);
            end = support.roundEndForSurrogate(text, end);
            if (end <= pos) {
                end = hardEnd;
            }
            boolean finalPiece = end >= blockEnd;
            if (finalPiece && blockEnd < text.length() && text.charAt(blockEnd) == '\n') {
                // 包含块后的分隔换行，避免该字符在覆盖统计中漏覆盖。
                end = blockEnd + 1;
            }

            List<ChunkSource> sources =
                    support.resolveSources(document.getBlocks(), contentStart, end);
            Chunk chunk = new Chunk(context.documentId(), context.title(), context.version(),
                    ChunkStrategy.STRUCTURE, chunks.size(), text.substring(contentStart, end),
                    contentStart, end, sources);
            chunks.add(chunk);

            if (finalPiece) {
                break;
            }
            pos = end;
        }
    }
}
