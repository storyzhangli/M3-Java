package hybrideval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * hybrideval 环境探测：三态（DUAL / BLOCKED / UNREACHABLE），<b>无内存兜底</b>。
 *
 * <p>与 D37 EnvProbe 的差异（任务书 P4.1/§3.6）：对照组必须与基线同链路
 * （应用 HTTP /retrieval/search），实验组走 /hybrid/retrieval/search；
 * 任一端点不可用即 BLOCKED——禁止降级内存索引（内存语义与 PG 过滤/阈值下推不一致，
 * 降级会破坏 H3 控制变量）。模式维度（OFFLINE/FULL）判定保持配置级推断。</p>
 */
public final class EnvProbe {

    public static final String BACKEND_DUAL = "DUAL_STRATEGIES";
    public static final String BACKEND_BLOCKED = "BLOCKED";
    public static final String BACKEND_UNREACHABLE = "UNREACHABLE";
    public static final String MODE_OFFLINE = "OFFLINE";
    public static final String MODE_FULL = "FULL";

    /** 探测输入（全部布尔事实）。 */
    public record ProbeInput(boolean apiKeyPresent, boolean modeDeepseek, boolean pgPortReachable,
                             boolean appUp, boolean vectorEndpointOk, boolean hybridEndpointOk,
                             boolean chatModelPresent, boolean corpusOk) {
    }

    /** 判定输出。 */
    public record ProbeResult(String mode, String backend, String degradeReason) {
    }

    /** 纯函数判定（SelfTest 可独立自测）。 */
    public static ProbeResult classify(ProbeInput in) {
        String backend;
        String reason;
        if (!in.appUp()) {
            backend = BACKEND_UNREACHABLE;
            reason = "应用未启动/不可达：无内存兜底，评测 BLOCKED（§3.6：对照必须同链路）";
        } else if (!in.corpusOk()) {
            backend = BACKEND_BLOCKED;
            reason = "冻结语料/数据集校验失败，评测 BLOCKED";
        } else if (!in.pgPortReachable()) {
            backend = BACKEND_BLOCKED;
            reason = "PG 端口不可达：两策略同时失效（local 轨依赖 PG），评测 BLOCKED";
        } else if (in.vectorEndpointOk() && in.hybridEndpointOk()) {
            backend = BACKEND_DUAL;
            reason = "两策略端点探测均成功（/retrieval/search + /hybrid/retrieval/search）";
        } else if (!in.hybridEndpointOk()) {
            backend = BACKEND_BLOCKED;
            reason = "hybrid 端点探测失败（hybrid.enabled 未开启或链路异常）：禁止单轨降级对比";
        } else {
            backend = BACKEND_BLOCKED;
            reason = "向量端点探测失败：对照组缺失，无法构成控制变量对比";
        }
        boolean full = in.apiKeyPresent() && in.modeDeepseek() && in.chatModelPresent()
                && in.pgPortReachable() && in.appUp() && in.vectorEndpointOk() && in.hybridEndpointOk();
        String mode = full ? MODE_FULL : MODE_OFFLINE;
        if (!full) {
            StringBuilder r = new StringBuilder();
            if (!in.apiKeyPresent()) {
                r.append("AI_API_KEY 缺失;");
            }
            if (!in.modeDeepseek()) {
                r.append("AI_MODE 非 deepseek;");
            }
            if (!in.chatModelPresent()) {
                r.append("无 ChatModel;");
            }
            if (r.isEmpty()) {
                r.append("检索链路不可用;");
            }
            reason = reason + "；离线原因：" + r;
        }
        return new ProbeResult(mode, backend, reason);
    }

    /** TCP 端口探测（显式超时，不重试）。 */
    public static boolean tcpReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 写 env-probe.json。 */
    public static ObjectNode toJson(ProbeInput in, ProbeResult result, ObjectMapper mapper,
                                    String pgHost, int pgPort, String baseUrl) {
        ObjectNode n = mapper.createObjectNode();
        n.put("probedAt", OffsetDateTime.now().toString());
        n.put("pgHost", pgHost);
        n.put("pgPort", pgPort);
        n.put("baseUrl", baseUrl);
        ObjectNode dims = n.putObject("dimensions");
        dims.put("apiKeyPresent", in.apiKeyPresent());
        dims.put("modeDeepseek", in.modeDeepseek());
        dims.put("pgPortReachable", in.pgPortReachable());
        dims.put("appUp", in.appUp());
        dims.put("vectorEndpointOk", in.vectorEndpointOk());
        dims.put("hybridEndpointOk", in.hybridEndpointOk());
        dims.put("chatModelPresent", in.chatModelPresent());
        dims.put("chatModelInferred", true);
        dims.put("corpusOk", in.corpusOk());
        n.put("mode", result.mode());
        n.put("retrievalBackend", result.backend());
        n.put("degradeReason", result.degradeReason());
        n.put("note", "无内存兜底（P4.1）：对照必须走与 D37 基线同链路；chatModelPresent 为配置级推断，"
                + "探测零模型调用、零远程 Embedding。");
        return n;
    }

    public static void write(Path file, ObjectNode node, ObjectMapper mapper) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }
}
