package com.zl.d43.document.table.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 表格抽取配置（前缀 {@code app.ai.document.table}，H2 冻结默认值）：
 * {@code enabled=true}（表格分支默认开）、{@code ocr.enabled=false}
 * （无引擎，仅接口占位；开启亦不改变行为，诚实标注能力边界）。
 *
 * <p>装配方式与 {@code DocumentImportProperties} 同型（由既有
 * {@code @ConfigurationPropertiesScan}/{@code EnableConfigurationProperties}
 * 机制注册，本类不加 {@code @Component} 避免双注册）。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.document.table")
public class TableProperties {

    /** 表格抽取分支总开关（默认 true；false=导入钩子旁路，零表写，H1 回归零差异口径）。 */
    private boolean enabled = true;

    /** OCR 接口占位（默认 false；当前无引擎，true 不产生任何 OCR 行为）。 */
    private Ocr ocr = new Ocr();

    @Data
    public static class Ocr {
        /** OCR 开关（H3：R01 恒 false 生效路径；禁引入 OCR 引擎）。 */
        private boolean enabled = false;
    }
}
