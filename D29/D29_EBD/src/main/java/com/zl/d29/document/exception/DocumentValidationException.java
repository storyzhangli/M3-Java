package com.zl.d29.document.exception;

/**
 * 文档校验异常（空文件、超大、非法类型、非法编码、读取失败等）。
 *
 * <p>继承 {@link IllegalArgumentException}，由现有全局异常处理器归一为
 * {@code PARAM_INVALID}；消息为安全可读文案，不包含堆栈、密钥或本机路径。</p>
 */
public class DocumentValidationException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public DocumentValidationException(String message) {
        super(message);
    }
}
