package com.zl.d42.document.sync.model;

/**
 * 同步操作原因码（KP4 冻结 6 项；索引侧 EMBED_WRITE_FAILED/COUNT_MISMATCH/NO_SNAPSHOT
 * 与导入侧 PARSE_FAILED 保留既有存储位与语义，不在本枚举，防双实现漂移）。
 *
 * <p>异常→原因码分类规则（sync-params.json 冻结）：连接类异常→{@link #PG_UNREACHABLE}；
 * 其余存储层异常→{@link #PG_DELETE_FAILED}；双端确认非零→{@link #CONFIRM_MISMATCH}；
 * 标记事务失败→{@link #MARK_FAILED}；关联缺但 PG 向量在→{@link #SNAPSHOT_MISSING}
 * （转 orphan 管线）；重试达上限→{@link #RETRY_EXHAUSTED}。</p>
 */
public enum SyncReasonCode {

    /** PG 连接失败（运行期装饰器注入/真实掉线/坏端口）。 */
    PG_UNREACHABLE,

    /** PG 删除语句异常（非连接类，如语法/权限/超时）。 */
    PG_DELETE_FAILED,

    /** 双端确认失败：应清零的 PG count 或关联行数非零。 */
    CONFIRM_MISMATCH,

    /** 元数据标记（lifecycle→DEPRECATED）+ sync PENDING 事务失败。 */
    MARK_FAILED,

    /** 清理时关联行缺但 PG 向量在（Outbox 快照缺失，转 orphan 补偿管线）。 */
    SNAPSHOT_MISSING,

    /** 重试达 maxAttempts 仍失败（待人工；ADMIN 可经 retry 端点重置续试）。 */
    RETRY_EXHAUSTED;

    /** 存储值（空串=无原因，成功收敛态）。 */
    public static final String NONE = "";

    public static SyncReasonCode fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (SyncReasonCode code : values()) {
            if (code.name().equals(name.trim())) {
                return code;
            }
        }
        return null;
    }

    /**
     * 异常分类（KP4 冻结规则）：沿 cause 链识别连接类特征。
     *
     * <p>识别口径与 D39 T8 实测一致：CannotGetJdbcConnectionException /
     * CommunicationsException / PSQLException(connection) / SocketTimeoutException /
     * ConnectException / 受控注入异常 SIMULATED_PG_CONNECTION_LOST。</p>
     */
    public static SyncReasonCode classify(Throwable ex) {
        Throwable cursor = ex;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            String message = cursor.getMessage() == null ? "" : cursor.getMessage();
            String type = cursor.getClass().getName();
            if (message.contains("SIMULATED_PG_CONNECTION_LOST")
                    || message.contains("Connection refused")
                    || message.contains("connection")
                    || type.contains("CannotGetJdbcConnection")
                    || type.contains("CommunicationsException")
                    || type.contains("SocketTimeoutException")
                    || type.contains("ConnectException")
                    || type.contains("SQLTransientException")) {
                return PG_UNREACHABLE;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return PG_DELETE_FAILED;
    }
}
