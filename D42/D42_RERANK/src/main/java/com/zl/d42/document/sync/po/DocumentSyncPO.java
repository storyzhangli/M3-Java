package com.zl.d42.document.sync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作级同步状态表 {@code d42_document_sync} 的持久化对象（任务书 D1 裁决 + KP4 冻结）。
 *
 * <p>与 {@code document_vector_index}（索引任务态，管入库）正交共存：
 * 本表管删除/旧版本清理的跨系统收敛事实——失败必落库、可查询、可重试、可收敛。</p>
 */
@Data
@NoArgsConstructor
@TableName("d42_document_sync")
public class DocumentSyncPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    /** DELETE / UPDATE_CLEANUP（字符串承载枚举名，落库 ENUM 约束校验）。 */
    private String operation;

    /** 0=全版本（DELETE 语义，KP4 裁决 1）；>0=目标旧版本（UPDATE_CLEANUP）。 */
    private Integer version;

    /** PENDING / SYNCED / FAILED。 */
    private String syncStatus;

    /** 原因码（成功收敛=空串；耗尽=RETRY_EXHAUSTED）。 */
    private String reasonCode;

    private Integer attemptCount;

    /** 下次计划重试时间；NULL=不等待（SYNCED 或待手动）。 */
    private LocalDateTime nextRetryAt;

    /** 操作快照指纹：受影响 chunkId 集排序连接 SHA-256（重复事件与幂等证明引用）。 */
    private String payloadHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
