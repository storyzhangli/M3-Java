package com.zl.d31.document.vectorindex.dto;

import com.zl.d31.document.po.DocumentChunkIndexPO;
import lombok.Data;

/**
 * Chunk 关联行视图。
 */
@Data
public class ChunkIndexView {

    private String documentId;

    private Integer version;

    private String strategy;

    private String chunkId;

    private Integer chunkIndex;

    private String chunkContentHash;

    private Integer charLength;

    private Integer globalStart;

    private Integer globalEnd;

    private String status;

    public static ChunkIndexView from(DocumentChunkIndexPO po) {
        ChunkIndexView view = new ChunkIndexView();
        view.setDocumentId(po.getDocumentId());
        view.setVersion(po.getVersion());
        view.setStrategy(po.getStrategy());
        view.setChunkId(po.getChunkId());
        view.setChunkIndex(po.getChunkIndex());
        view.setChunkContentHash(po.getChunkContentHash());
        view.setCharLength(po.getCharLength());
        view.setGlobalStart(po.getGlobalStart());
        view.setGlobalEnd(po.getGlobalEnd());
        view.setStatus(po.getStatus());
        return view;
    }
}
