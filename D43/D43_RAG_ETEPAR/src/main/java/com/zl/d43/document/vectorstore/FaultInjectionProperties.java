package com.zl.d43.document.vectorstore;

import com.zl.d43.document.vectorstore.FaultInjectionConfiguration;
import com.zl.d43.document.vectorstore.SimulatedConnectionLostException;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P5（H2/H5，KP3 冻结）：受控故障注入参数。
 *
 * <p>前缀 {@code app.vector-index.fault-injection}，主 profile 永不装配
 * （{@link FaultInjectionConfiguration} 无 matchIfMissing，默认 false）。</p>
 *
 * <p>触发语义（冻结）：{@code failAddAttempt}/{@code failDeleteAttempt} 为对应方法的
 * <b>调用序号</b>（从 1 起），命中当次抛 {@link SimulatedConnectionLostException} 且
 * <b>仅抛一次</b>（消耗式，天然支持"注入失败→关闭注入重跑收敛"矩阵节奏）；
 * 0=该类注入禁用。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.vector-index.fault-injection")
public class FaultInjectionProperties {

    /** 装配总开关：true 才注册装饰器（默认 false）。 */
    private boolean enabled = false;

    /** 第 N 次 add() 注入失败；0=禁用。 */
    private int failAddAttempt = 0;

    /** 第 N 次 delete(List) 注入失败；0=禁用。 */
    private int failDeleteAttempt = 0;
}
