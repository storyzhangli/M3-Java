package com.zl.d30.document.embedding.dto;

import com.zl.d30.document.embedding.dto.CompatibilityCheckRequest;
import com.zl.d30.document.embedding.dto.CompatibilityDecisionView;
import com.zl.d30.document.embedding.dto.EmbeddingRecordView;
import com.zl.d30.document.embedding.model.CompatibilityDecision;
import com.zl.d30.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d30.document.embedding.model.EmbeddingRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 视图映射器：领域模型 → 对外视图，PO/向量不直接暴露。
 */
@Component
public class EmbeddingViewMapper {

    /** 向量预览保留的维度数。 */
    private static final int PREVIEW_SIZE = 5;

    public EmbeddingRecordView toRecordView(EmbeddingRecord record) {
        EmbeddingRecordView view = new EmbeddingRecordView();
        view.setDocumentId(record.getDocumentId());
        view.setTitle(record.getTitle());
        view.setVersion(record.getVersion());
        view.setStrategy(record.getStrategy());
        view.setChunkIndex(record.getChunkIndex());
        view.setModelName(record.getModelName());
        view.setDimensions(record.getDimensions());
        view.setVectorSpaceId(record.getVectorSpaceId());
        view.setVectorPreview(buildPreview(record.getVector()));
        view.setL2Norm(l2Norm(record.getVector()));
        return view;
    }

    public List<EmbeddingRecordView> toRecordViews(List<EmbeddingRecord> records) {
        List<EmbeddingRecordView> views = new ArrayList<>(records.size());
        for (EmbeddingRecord record : records) {
            views.add(toRecordView(record));
        }
        return views;
    }

    public EmbeddingModelMetadataView toMetadataView(EmbeddingModelMetadata metadata) {
        EmbeddingModelMetadataView view = new EmbeddingModelMetadataView();
        view.setProvider(metadata.getProvider());
        view.setModelName(metadata.getModelName());
        view.setModelVersion(metadata.getModelVersion());
        view.setDimensions(metadata.getDimensions());
        view.setVectorSpaceId(metadata.getVectorSpaceId());
        view.setIndexVersion(metadata.getIndexVersion());
        view.setDistanceMetric(metadata.getDistanceMetric() == null ? null : metadata.getDistanceMetric().name());
        return view;
    }

    /**
     * 由兼容性检查请求构造候选元数据，并补齐 vectorSpaceId。
     */
    public EmbeddingModelMetadata toCandidate(CompatibilityCheckRequest request) {
        String vectorSpaceId = EmbeddingModelMetadata.buildVectorSpaceId(
                request.getProvider(), request.getModelName(), request.getModelVersion(), request.getDimensions());
        return EmbeddingModelMetadata.builder()
                .provider(request.getProvider())
                .modelName(request.getModelName())
                .modelVersion(request.getModelVersion())
                .dimensions(request.getDimensions())
                .vectorSpaceId(vectorSpaceId)
                .indexVersion(request.getIndexVersion())
                .build();
    }

    public CompatibilityDecisionView toDecisionView(CompatibilityDecision decision,
                                                    EmbeddingModelMetadata current,
                                                    EmbeddingModelMetadata candidate) {
        CompatibilityDecisionView view = new CompatibilityDecisionView();
        view.setDecision(decision.name());
        view.setCompatible(decision.isCompatible());
        view.setReason(decision.getReason());
        view.setCurrentIndex(toMetadataView(current));
        view.setCandidate(toMetadataView(candidate));
        return view;
    }

    private List<Float> buildPreview(float[] vector) {
        int size = Math.min(PREVIEW_SIZE, vector.length);
        List<Float> preview = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            preview.add(vector[i]);
        }
        return preview;
    }

    private double l2Norm(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }
}
