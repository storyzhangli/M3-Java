package com.zl.d40.document.vectorstore;

/**
 * D40 P5（H5/KP3 冻结）：受控故障注入专用异常——模拟 PG 连接在客户端边界丢失。
 *
 * <p>失败点位于应用侧 VectorStore 调用边界（装饰器抛出），语义等价于运行期
 * PG 连接中断；PG 本体真实数据不受影响（H5：禁止停服务/防火墙/DROP 等破坏性手段）。</p>
 *
 * <p>message 固定 {@code SIMULATED_PG_CONNECTION_LOST}，作为矩阵取证的稳定匹配串；
 * {@code SyncReasonCode.classify} 对该哨兵 message 直接归因
 * {@code PG_UNREACHABLE}（KP4 冻结识别口径），无需包装 SQLException。</p>
 */
public class SimulatedConnectionLostException extends RuntimeException {

    public static final String SENTINEL = "SIMULATED_PG_CONNECTION_LOST";

    public SimulatedConnectionLostException() {
        super(SENTINEL);
    }

    public SimulatedConnectionLostException(String message) {
        super(message);
    }
}
