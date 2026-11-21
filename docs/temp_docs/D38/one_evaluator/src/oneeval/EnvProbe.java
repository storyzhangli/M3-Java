package oneeval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * one_ 轨环境自动探测：双模式（离线/全量）× 检索后端三态
 * （ONE_PGVECTOR / UNREACHABLE / BLOCKED）。两维度独立判定；探测确定性。
 *
 * <p>D38 改造：删除 IN_MEMORY_LOCAL 降级——神经 Embedding 无法本地复刻，
 * 应用/PG 不可达 → UNREACHABLE；one_ 端点未开启或检索链路探测失败 → BLOCKED；
 * 不存在任何内存兜底。探测查询本身产生真实 one_ 调用（计入预算）。</p>
 */
public final class EnvProbe {

    public static final String BACKEND_ONE_PGVECTOR = "ONE_PGVECTOR";
    public static final String BACKEND_UNREACHABLE = "UNREACHABLE";
    public static final String BACKEND_BLOCKED = "BLOCKED";
    public static final String MODE_OFFLINE = "OFFLINE";
    public static final String MODE_FULL = "FULL";

    /** 探测输入（全部布尔事实，来源见 probe()）。oneEndpointOk=usage 端点真实可达。 */
    public record ProbeInput(boolean apiKeyPresent, boolean modeDeepseek, boolean pgPortReachable,
                             boolean appUp, boolean appRetrievalOk, boolean chatModelPresent,
                             boolean oneEndpointOk, boolean corpusOk) {
    }

    /** 判定输出。 */
    public record ProbeResult(String mode, String backend, String degradeReason) {
    }

    /**
     * 纯函数模式识别（可独立自测）。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>应用未启动或 PG 端口不可达 → UNREACHABLE（无内存降级）；</li>
     *   <li>one_ 端点未开启 / 检索链路探测失败 / 语料不可初始化 → BLOCKED；</li>
     *   <li>应用检索链路真实可用 + PG 可达 + one_ 开启 → ONE_PGVECTOR；</li>
     *   <li>模式：有密钥 + deepseek + ChatModel + PG 可达 + 应用检索可用 → FULL；否则 OFFLINE。</li>
     * </ul>
     */
    public static ProbeResult classify(ProbeInput in) {
        String backend;
        String reason;
        if (!in.appUp() || !in.pgPortReachable()) {
            backend = BACKEND_UNREACHABLE;
            reason = !in.appUp() ? "应用未启动，one_ 轨无内存降级，检索类指标 UNREACHABLE"
                    : "PG 端口不可达，one_ 轨无内存降级，检索类指标 UNREACHABLE";
        } else if (!in.oneEndpointOk()) {
            backend = BACKEND_BLOCKED;
            reason = "one_ 端点未开启（app.one.embedding.enabled=false 或装配失败），检索类指标 BLOCKED";
        } else if (!in.appRetrievalOk()) {
            backend = BACKEND_BLOCKED;
            reason = "应用检索链路探测失败（PG 端口可达但 one_ 链路异常），检索类指标 BLOCKED";
        } else if (!in.corpusOk()) {
            backend = BACKEND_BLOCKED;
            reason = "冻结语料无法初始化，检索类指标 BLOCKED";
        } else {
            backend = BACKEND_ONE_PGVECTOR;
            reason = "one_ 检索链路真实可用（探测查询成功），PG 端口可达";
        }
        boolean full = in.apiKeyPresent() && in.modeDeepseek() && in.chatModelPresent()
                && in.pgPortReachable() && in.appUp() && in.appRetrievalOk();
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

    /** 执行真实探测并写 env-probe.json。appRetrievalOk 由调用方用真实 one_ 探测查询回填。 */
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
        dims.put("appRetrievalOk", in.appRetrievalOk());
        dims.put("chatModelPresent", in.chatModelPresent());
        dims.put("chatModelInferred", true);
        dims.put("oneEndpointOk", in.oneEndpointOk());
        dims.put("corpusOk", in.corpusOk());
        n.put("mode", result.mode());
        n.put("retrievalBackend", result.backend());
        n.put("degradeReason", result.degradeReason());
        n.put("note", "chatModelPresent 为配置级推断（mock 无 Bean；deepseek+密钥且应用已启动则 Bean 存在），"
                + "非 Bean 容器直检；one_ 探测查询产生真实远程调用并计入预算；无内存降级后端。");
        return n;
    }

    public static void write(Path file, ObjectNode node, ObjectMapper mapper) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }
}