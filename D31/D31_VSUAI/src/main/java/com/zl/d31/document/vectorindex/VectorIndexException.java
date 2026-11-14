package com.zl.d31.document.vectorindex;

/**
 * 向量索引处理异常：在状态已持久化（含错误码/阶段）后抛出，
 * 便于调用方感知失败，同时不丢失可恢复的现场。
 */
public class VectorIndexException extends RuntimeException {

    private final String errorCode;

    public VectorIndexException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
