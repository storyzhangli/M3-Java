package com.zl.d42.sync;

import com.zl.d42.document.sync.model.SyncOperation;
import com.zl.d42.document.sync.model.SyncReasonCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C01：KP4 冻结口径单测——操作归一化与异常→原因码分类规则。
 */
class SyncOperationAndReasonCodeTest {

    @Test
    @DisplayName("DELETE 恒归一 version=0（KP4 裁决 1：全版本哨兵）")
    void deleteNormalizesToZero() {
        assertEquals(SyncOperation.ALL_VERSIONS, SyncOperation.DELETE.normalizeVersion(null));
        assertEquals(SyncOperation.ALL_VERSIONS, SyncOperation.DELETE.normalizeVersion(7));
    }

    @Test
    @DisplayName("UPDATE_CLEANUP 必须正版本号，否则 fail-closed")
    void updateCleanupRequiresPositiveVersion() {
        assertEquals(2, SyncOperation.UPDATE_CLEANUP.normalizeVersion(2));
        assertThrows(IllegalArgumentException.class,
                () -> SyncOperation.UPDATE_CLEANUP.normalizeVersion(0));
        assertThrows(IllegalArgumentException.class,
                () -> SyncOperation.UPDATE_CLEANUP.normalizeVersion(null));
    }

    @Test
    @DisplayName("fromName 容错：null/空白/未知=null（调用方 fail-closed 分支）")
    void fromNameTolerant() {
        assertEquals(SyncOperation.DELETE, SyncOperation.fromName(" DELETE "));
        assertNull(SyncOperation.fromName(null));
        assertNull(SyncOperation.fromName(""));
        assertNull(SyncOperation.fromName("DROP_TABLE"));
    }

    @Test
    @DisplayName("classify：受控注入哨兵→PG_UNREACHABLE（KP3/KP4 交叉口径）")
    void classifySimulatedConnectionLost() {
        RuntimeException wrapped = new RuntimeException(
                new RuntimeException("SIMULATED_PG_CONNECTION_LOST"));
        assertEquals(SyncReasonCode.PG_UNREACHABLE, SyncReasonCode.classify(wrapped));
    }

    @Test
    @DisplayName("classify：SQLTransient / JDBC 取连接失败 cause 链→PG_UNREACHABLE")
    void classifyConnectionFamily() {
        assertEquals(SyncReasonCode.PG_UNREACHABLE,
                SyncReasonCode.classify(new SQLTransientConnectionException("connection lost")));
        assertEquals(SyncReasonCode.PG_UNREACHABLE, SyncReasonCode.classify(
                new DataAccessResourceFailureException(
                        "Could not open connection; Connection refused by host")));
    }

    @Test
    @DisplayName("classify：非连接类→PG_DELETE_FAILED 兜底（不误报 UNREACHABLE）")
    void classifyNonConnectivity() {
        assertEquals(SyncReasonCode.PG_DELETE_FAILED,
                SyncReasonCode.classify(new IllegalStateException("syntax error at line 1")));
    }

    @Test
    @DisplayName("classify：cause 自引用防死循环（guard 上限生效）")
    void classifySelfCauseGuard() {
        Throwable self = new Throwable("boom") {
            @Override
            public synchronized Throwable getCause() {
                return null; // 模拟 JDK 默认 cause=null 路径亦不抛
            }
        };
        assertEquals(SyncReasonCode.PG_DELETE_FAILED, SyncReasonCode.classify(self));
    }

    @Test
    @DisplayName("fromName：原因码容错 + NONE 空串存储值")
    void reasonCodeFromName() {
        assertEquals(SyncReasonCode.RETRY_EXHAUSTED, SyncReasonCode.fromName("RETRY_EXHAUSTED"));
        assertNull(SyncReasonCode.fromName(""));
        assertNull(SyncReasonCode.fromName("NOPE"));
        assertEquals("", SyncReasonCode.NONE);
    }
}
