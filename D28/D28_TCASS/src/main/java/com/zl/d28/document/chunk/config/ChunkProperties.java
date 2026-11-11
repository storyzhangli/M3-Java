package com.zl.d28.document.chunk.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chunk 切分配置（前缀 {@code app.ai.chunk}）。
 *
 * <p>配置与切分算法分离：本类只做绑定、默认值与校验，不在业务代码中散落参数。
 * 长度单位固定为 UTF-16 code unit（{@link #lengthUnit}），<b>不是 Token 数</b>。</p>
 *
 * <table>
 *   <caption>参数说明</caption>
 *   <tr><th>参数</th><th>默认</th><th>取值范围/校验</th></tr>
 *   <tr><td>chunkSize</td><td>500</td><td>1..100000</td></tr>
 *   <tr><td>chunkOverlap</td><td>100</td><td>0.. 且必须 &lt; chunkSize</td></tr>
 *   <tr><td>minChunkSize</td><td>50</td><td>1..chunkSize</td></tr>
 *   <tr><td>maxChunkSize</td><td>1000</td><td>&ge; chunkSize</td></tr>
 *   <tr><td>boundaryTolerance</td><td>100</td><td>0..chunkSize</td></tr>
 *   <tr><td>mergeShortBlocks</td><td>true</td><td>布尔</td></tr>
 * </table>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.chunk")
public class ChunkProperties {

    /** 长度单位常量：本迭代固定 UTF-16 code unit，不提供 Token 口径。 */
    public static final String LENGTH_UNIT = "UTF16_CODE_UNIT";

    /** 固定长度切分的目标 Chunk 大小（UTF-16），默认 500。 */
    private int chunkSize = 500;

    /** 相邻 Chunk 重叠长度（UTF-16），默认 100；必须严格小于 chunkSize。 */
    private int chunkOverlap = 100;

    /** 最小 Chunk 长度（UTF-16），默认 50；用于末尾碎片合并判断。 */
    private int minChunkSize = 50;

    /** 结构切分最大 Chunk 长度（UTF-16），默认 1000；超长段落据此二次切分。 */
    private int maxChunkSize = 1000;

    /** 语义边界保护的回退容差（UTF-16），默认 100；在该范围内寻找换行/空格切点。 */
    private int boundaryTolerance = 100;

    /** 是否在结构切分中合并连续短段落，默认开启。 */
    private boolean mergeShortBlocks = true;

    /** 长度单位标识，固定为 UTF16_CODE_UNIT；仅用于对外明示口径。 */
    private String lengthUnit = LENGTH_UNIT;

    /**
     * 启动快速失败：参数非法时直接抛出，避免运行期才暴露。
     */
    @PostConstruct
    void checkOnStartup() {
        validate();
    }

    /**
     * 显式校验配置。
     *
     * @throws IllegalArgumentException 任一参数越界或相互约束不满足
     */
    public void validate() {
        if (chunkSize <= 0 || chunkSize > 100_000) {
            throw new IllegalArgumentException("chunkSize 必须在 1..100000 之间，当前: " + chunkSize);
        }
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("chunkOverlap 不能为负，当前: " + chunkOverlap);
        }
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "chunkOverlap 必须严格小于 chunkSize，overlap=" + chunkOverlap + ", size=" + chunkSize);
        }
        if (minChunkSize <= 0 || minChunkSize > chunkSize) {
            throw new IllegalArgumentException(
                    "minChunkSize 必须在 1..chunkSize 之间，min=" + minChunkSize + ", size=" + chunkSize);
        }
        if (maxChunkSize < chunkSize) {
            throw new IllegalArgumentException(
                    "maxChunkSize 必须 >= chunkSize，max=" + maxChunkSize + ", size=" + chunkSize);
        }
        if (boundaryTolerance < 0 || boundaryTolerance > chunkSize) {
            throw new IllegalArgumentException(
                    "boundaryTolerance 必须在 0..chunkSize 之间，tolerance=" + boundaryTolerance);
        }
        if (!LENGTH_UNIT.equals(lengthUnit)) {
            throw new IllegalArgumentException("本迭代长度单位固定为 UTF16_CODE_UNIT，当前: " + lengthUnit);
        }
    }
}
