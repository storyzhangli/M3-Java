package com.zl.d30.document.dto;

import lombok.Data;

/**
 * 文件导入结果视图。
 *
 * <p>{@code duplicate=true} 表示命中 contentHash 重复，未创建第二行、未进入第二次处理。</p>
 */
@Data
public class DocumentImportView {

    private boolean duplicate;

    private DocumentView document;
}
