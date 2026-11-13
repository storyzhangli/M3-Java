package com.zl.d30.document.vectorstore.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 通过 TCP 连通性判断 pgvector 是否可用的 JUnit 条件。
 */
public class PgVectorReachableCondition implements ExecutionCondition {

    private static final String DEFAULT_HOST = "192.168.200.153";

    private static final int DEFAULT_PORT = 5432;

    private static final int TIMEOUT_MS = 1000;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String host = System.getProperty("pgvector.host", DEFAULT_HOST);
        int port = Integer.parseInt(System.getProperty("pgvector.port", String.valueOf(DEFAULT_PORT)));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return ConditionEvaluationResult.enabled("pgvector 可达：%s:%d".formatted(host, port));
        } catch (Exception ex) {
            return ConditionEvaluationResult.disabled(
                    "pgvector 不可达，集成测试禁用：%s:%d（%s）".formatted(host, port, ex.getMessage()));
        }
    }
}
