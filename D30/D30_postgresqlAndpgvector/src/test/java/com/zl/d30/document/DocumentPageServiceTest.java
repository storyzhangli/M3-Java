package com.zl.d30.document;

import com.zl.d30.document.mapper.DocumentPageContentMapper;
import com.zl.d30.document.model.DocumentPageContent;
import com.zl.d30.document.po.DocumentPageContentPO;
import com.zl.d30.document.service.DocumentPageService;
import com.zl.d30.document.support.DocumentConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 页面级内容服务测试：替换写入顺序与来源定位查询。
 */
class DocumentPageServiceTest {

    private DocumentPageContentMapper mapper;
    private DocumentPageService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DocumentPageContentMapper.class);
        service = new DocumentPageService(mapper, new DocumentConverter());
    }

    @Test
    void replacePagesDeletesThenInserts() {
        DocumentPageContent p1 = page("doc-1", 1, "a");
        DocumentPageContent p2 = page("doc-1", 2, "b");

        service.replacePages(List.of(p1, p2));

        verify(mapper).delete(any());
        verify(mapper, times(2)).insert(any(DocumentPageContentPO.class));
    }

    @Test
    void replaceEmptyListDoesNothing() {
        service.replacePages(List.of());
        verifyNoInteractions(mapper);
    }

    @Test
    void listPagesReturnsDomains() {
        DocumentPageContentPO po = new DocumentPageContentPO();
        po.setDocumentId("doc-1");
        po.setPageNumber(1);
        po.setContent("text");
        po.setBlank(0);
        when(mapper.selectList(any())).thenReturn(List.of(po));

        List<DocumentPageContent> result = service.listPages("doc-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("text");
        assertThat(result.get(0).getBlank()).isFalse();
    }

    @Test
    void getPageReturnsSingleDomain() {
        DocumentPageContentPO po = new DocumentPageContentPO();
        po.setDocumentId("doc-1");
        po.setPageNumber(3);
        po.setBlank(1);
        when(mapper.selectOne(any())).thenReturn(po);

        DocumentPageContent result = service.getPage("doc-1", 3);

        assertThat(result.getPageNumber()).isEqualTo(3);
        assertThat(result.getBlank()).isTrue();
    }

    private DocumentPageContent page(String documentId, int number, String content) {
        DocumentPageContent page = new DocumentPageContent();
        page.setDocumentId(documentId);
        page.setPageNumber(number);
        page.setContent(content);
        page.setContentLength(content.length());
        page.setBlank(false);
        return page;
    }
}
