package com.zl.d31.document.exception;

/**
 * 文档基础解析异常。
 *
 * <p>表示文件已通过校验，但在最小解析（UTF-8 解码后的可用性判断）阶段失败，
 * 由导入服务捕获并将状态收敛为 {@code FAILED}，不向上伪造成功。</p>
 */
public class DocumentParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
