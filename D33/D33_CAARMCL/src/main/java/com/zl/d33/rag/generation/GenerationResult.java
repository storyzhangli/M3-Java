package com.zl.d33.rag.generation;

import com.zl.d33.rag.model.GenerationStatus;
import com.zl.d33.rag.model.RagErrorCode;

/**
 * 生成结果：状态、内容、错误码与安全说明。
 *
 * @param status                  生成状态
 * @param content                 模型回答（失败时为 null）
 * @param errorCode               稳定错误码
 * @param errorMessage            安全错误说明
 * @param unknownCitationDetected 是否引用了上下文之外的未知来源标识
 * @param elapsedMs               生成耗时
 */
public record GenerationResult(GenerationStatus status,
                               String content,
                               RagErrorCode errorCode,
                               String errorMessage,
                               boolean unknownCitationDetected,
                               long elapsedMs) {

    public static GenerationResult success(String content, boolean unknownCitation, long elapsedMs) {
        return new GenerationResult(GenerationStatus.SUCCESS, content, RagErrorCode.NONE,
                null, unknownCitation, elapsedMs);
    }

    public static GenerationResult failure(GenerationStatus status, RagErrorCode errorCode,
                                           String errorMessage, long elapsedMs) {
        return new GenerationResult(status, null, errorCode, errorMessage, false, elapsedMs);
    }
}
