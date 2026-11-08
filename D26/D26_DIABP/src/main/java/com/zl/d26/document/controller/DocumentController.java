package com.zl.d26.document.controller;

import com.zl.d26.common.Result;
import com.zl.d26.document.dto.DocumentImportView;
import com.zl.d26.document.dto.DocumentRegisterRequest;
import com.zl.d26.document.dto.DocumentView;
import com.zl.d26.document.service.DocumentImportService;
import com.zl.d26.document.service.DocumentRegistrationService;
import com.zl.d26.document.support.DocumentConverter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识文档入口：登记与文件导入职责分离。
 *
 * <p>HTTP 层统一返回 {@link Result}，业务结果通过 code 区分。</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentRegistrationService registrationService;
    private final DocumentImportService importService;
    private final DocumentConverter converter;

    public DocumentController(DocumentRegistrationService registrationService,
                              DocumentImportService importService,
                              DocumentConverter converter) {
        this.registrationService = registrationService;
        this.importService = importService;
        this.converter = converter;
    }

    /** 文档登记：仅元数据，落 PENDING。 */
    @PostMapping("/register")
    public Result<DocumentView> register(@Valid @RequestBody DocumentRegisterRequest request) {
        return Result.success(converter.toView(registrationService.register(request)));
    }

    /** 文件导入：服务端校验、去重、状态机与基础解析。 */
    @PostMapping("/import")
    public Result<DocumentImportView> importFile(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "documentId", required = false) String documentId,
                                                 @RequestParam(value = "source", required = false) String source,
                                                 @RequestParam(value = "title", required = false) String title) {
        return Result.success(importService.importFile(file, documentId, source, title));
    }

    @GetMapping("/{documentId}")
    public Result<DocumentView> get(@PathVariable("documentId") String documentId) {
        return Result.success(converter.toView(registrationService.getByDocumentId(documentId)));
    }

    @GetMapping
    public Result<List<DocumentView>> list() {
        List<DocumentView> views = registrationService.listAll().stream()
                .map(converter::toView).toList();
        return Result.success(views);
    }
}
