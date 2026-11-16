package com.zl.d33.rag.support;

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
        } catch (Exception ex) {
            return ConditionEvaluationResult.disabled(
                    "pgvector 不可达，集成测试禁用：%s:%d（%s）".formatted(host, port, ex.getMessage()));
        }

        // D33 真实启动读取 PG_VECTOR_PASSWORD（application.yml 第 219 行）。
        // 密码缺失时 Spring 上下文无法启动，这里干净禁用而非报错，避免误导；
        // 不读取/打印密码值，仅判断是否存在。
        String password = System.getenv("PG_VECTOR_PASSWORD");
        if (password == null || password.isBlank()) {
            return ConditionEvaluationResult.disabled(
                    "PG 密码环境变量 PG_VECTOR_PASSWORD 未设置，集成测试禁用（不冒充通过）");
        }
        return ConditionEvaluationResult.enabled("pgvector 可达且密码已注入：%s:%d".formatted(host, port));
    }
}
