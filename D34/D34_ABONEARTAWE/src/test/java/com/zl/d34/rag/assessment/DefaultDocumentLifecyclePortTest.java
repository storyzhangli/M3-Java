package com.zl.d34.rag.assessment;

import com.zl.d34.document.mapper.KnowledgeDocumentMapper;
import com.zl.d34.document.model.DocumentLifecycleStatus;
import com.zl.d34.document.po.KnowledgeDocumentPO;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultDocumentLifecyclePort} Fail-closed 单元测试（端口级，纯 Mockito）。
 *
 * <p>覆盖 T01–T06 与空白入参、恢复场景，核心断言：除确为 ACTIVE 外，
 * 文档不存在/查询失败/状态无法解析均不返回 ACTIVE。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultDocumentLifecyclePortTest {

    private static final String DOC_ID = "doc-1";

    @Mock
    private KnowledgeDocumentMapper mapper;

    private DefaultDocumentLifecyclePort port() {
        return new DefaultDocumentLifecyclePort(mapper);
    }

    /** T01：存储状态 ACTIVE，返回 ACTIVE 且有效。 */
    @Test
    void t01_activeDocumentResolvesActive() {
        when(mapper.selectOne(any())).thenReturn(po("ACTIVE"));

        DocumentLifecycleStatus status = port().resolve(DOC_ID);

        assertThat(status).isEqualTo(DocumentLifecycleStatus.ACTIVE);
        assertThat(status.isEffective()).isTrue();
    }

    /** T02：DRAFT 非有效，不返回 ACTIVE。 */
    @Test
    void t02_draftDocumentIsNotEffective() {
        when(mapper.selectOne(any())).thenReturn(po("DRAFT"));

        DocumentLifecycleStatus status = port().resolve(DOC_ID);

        assertThat(status).isEqualTo(DocumentLifecycleStatus.DRAFT);
        assertThat(status.isEffective()).isFalse();
    }

    /** T03：DEPRECATED 非有效，不返回 ACTIVE。 */
    @Test
    void t03_deprecatedDocumentIsNotEffective() {
        when(mapper.selectOne(any())).thenReturn(po("DEPRECATED"));

        DocumentLifecycleStatus status = port().resolve(DOC_ID);

        assertThat(status).isEqualTo(DocumentLifecycleStatus.DEPRECATED);
        assertThat(status.isEffective()).isFalse();
    }

    /** T04：文档不存在返回 NOT_FOUND，不默认 ACTIVE。 */
    @Test
    void t04_missingDocumentResolvesNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);

        DocumentLifecycleStatus status = port().resolve(DOC_ID);

        assertThat(status).isEqualTo(DocumentLifecycleStatus.NOT_FOUND);
        assertThat(status.isEffective()).isFalse();
    }

    /** T05：查询抛出 PersistenceException，返回 LOOKUP_FAILED，绝不 ACTIVE。 */
    @Test
    void t05_persistenceExceptionResolvesLookupFailed() {
        when(mapper.selectOne(any())).thenThrow(new PersistenceException("db down"));

        DocumentLifecycleStatus status = port().resolve(DOC_ID);

        assertThat(status).isEqualTo(DocumentLifecycleStatus.LOOKUP_FAILED);
        assertThat(status.isEffective()).isFalse();
    }

    /** T06：存储状态无法识别（含空白），返回 UNKNOWN，绝不 ACTIVE。 */
    @Test
    void t06_unrecognizedStoredValueResolvesUnknown() {
        when(mapper.selectOne(any())).thenReturn(po("WEIRD_STATUS"));

        DocumentLifecycleStatus status = port().resolve(DOC_ID);

        assertThat(status).isEqualTo(DocumentLifecycleStatus.UNKNOWN);
        assertThat(status.isEffective()).isFalse();
    }

    /** T06 补充：存储状态为空白，同样 UNKNOWN。 */
    @Test
    void blankStoredValueResolvesUnknown() {
        when(mapper.selectOne(any())).thenReturn(po(" "));

        DocumentLifecycleStatus status = port().resolve(DOC_ID);

        assertThat(status).isEqualTo(DocumentLifecycleStatus.UNKNOWN);
    }

    /** 空白 documentId 直接 UNKNOWN，不触发查询。 */
    @Test
    void blankDocumentIdResolvesUnknown() {
        DocumentLifecycleStatus status = port().resolve(" ");

        assertThat(status).isEqualTo(DocumentLifecycleStatus.UNKNOWN);
    }

    /** T09：查询恢复正常后，ACTIVE 文档重新解析为 ACTIVE。 */
    @Test
    void t09_recoversAfterFailure() {
        DefaultDocumentLifecyclePort port = port();
        when(mapper.selectOne(any()))
                .thenThrow(new PersistenceException("temporary failure"))
                .thenReturn(po("ACTIVE"));

        assertThat(port.resolve(DOC_ID)).isEqualTo(DocumentLifecycleStatus.LOOKUP_FAILED);
        assertThat(port.resolve(DOC_ID)).isEqualTo(DocumentLifecycleStatus.ACTIVE);
    }

    private KnowledgeDocumentPO po(String lifecycle) {
        KnowledgeDocumentPO po = new KnowledgeDocumentPO();
        po.setDocumentId(DOC_ID);
        po.setLifecycleStatus(lifecycle);
        return po;
    }
}
