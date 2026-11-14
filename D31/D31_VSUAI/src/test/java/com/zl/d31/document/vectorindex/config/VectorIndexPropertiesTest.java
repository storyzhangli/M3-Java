package com.zl.d31.document.vectorindex.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量索引配置单元测试：默认值与启动校验。
 */
class VectorIndexPropertiesTest {

    @Test
    void defaultsAreValid() {
        VectorIndexProperties properties = new VectorIndexProperties();
        assertDoesNotThrow(properties::validate);
        assertEquals("STRUCTURE", properties.getDefaultStrategy());
        assertEquals(100, properties.getBatchSize());
        assertEquals(5, properties.getMaxAttempts());
        assertEquals("split-v1", properties.getSplitVersion());
    }

    @Test
    void invalidBatchSizeIsRejected() {
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setBatchSize(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, properties::validate);
        assertTrue(ex.getMessage().contains("batch-size"));
    }

    @Test
    void invalidMaxAttemptsIsRejected() {
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setMaxAttempts(21);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void blankSplitVersionIsRejected() {
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setSplitVersion(" ");
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
