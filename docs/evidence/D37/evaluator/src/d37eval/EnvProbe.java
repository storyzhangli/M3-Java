package d37eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * 环境自动探测：双模式（离线/全量）× 检索后端三态（PGVECTOR/IN_MEMORY_LOCAL/BLOCKED）。
 * 两维度独立判定；探测确定性、不发起任何模型调用。
 */
public final class EnvProbe {

    public static final String BACKEND_PGVECTOR = "PGVECTOR";
    public static final String BACKEND_IN_MEMORY = "IN_MEMORY_LOCAL";
    public static final String BACKEND_BLOCKED = "BLOCKED";
    public static final String MODE_OFFLINE = "OFFLINE";
    public static final String MODE_FULL = "FULL";

    /** 探测输入（全部布尔事实，来源见 probe()）。 */
    public record ProbeInput(boolean apiKeyPresent, boolean modeDeepseek, boolean pgPortReachable,
                             boolean appUp, boolean appRetrievalOk, boolean chatModelPresent,
                             boolean localEmbeddingOk, boolean corpusOk) {
    }

    /** 判定输出。 */
    public record ProbeResult(String mode, String backend, String degradeReason) {
    }

    /**
     * 纯函数模式识别（C17 的 2×2 七场景判定核心，可独立自测）。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>后端：应用检索链路真实可用 → PGVECTOR；否则本地 Embedding+语料可初始化 → IN_MEMORY_LOCAL；否则 BLOCKED。</li>
     *   <li>模式：有密钥 + deepseek + ChatModel + PG 可达 + 应用检索可用 → FULL；否则 OFFLINE。</li>
     *   <li>AI_API_KEY 缺失不等于检索能力缺失；禁止仅凭配置 enabled=true 判定可用。</li>
     * </ul>
     */
    public static ProbeResult classify(ProbeInput in) {
        String backend;
        String reason;
        if (in.appUp() && in.appRetrievalOk() && in.pgPortReachable()) {
            backend = BACKEND_PGVECTOR;
            reason = "应用检索链路真实可用（探测查询成功），PG 端口可达";
        } else if (in.localEmbeddingOk() && in.corpusOk()) {
            backend = BACKEND_IN_MEMORY;
            if (!in.pgPortReachable()) {
                reason = "PG 端口不可达，降级内存索引（真实向量余弦，非数据库耗时）";
            } else if (!in.appUp()) {
                reason = "应用未启动，降级内存索引";
            } else {
                reason = "应用检索链路探测失败（PG 端口可达但链路异常），降级内存索引";
            }
        } else {
            backend = BACKEND_BLOCKED;
            reason = "本地 Embedding 或冻结语料无法初始化，检索类指标 BLOCKED";
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

    /** 本地 Embedding 初始化验证：真实计算一个样本向量，校验维度/非零/确定性。 */
    public static boolean localEmbeddingOk() {
        try {
            float[] a = LocalEmbedding.embed("数控机床主轴过热报警");
            float[] b = LocalEmbedding.embed("数控机床主轴过热报警");
            if (a.length != LocalEmbedding.DIMENSIONS || LocalEmbedding.isZero(a)) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 执行真实探测并写 env-probe.json。appRetrievalOk 由调用方用真实探测查询回填。 */
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
        dims.put("localEmbeddingOk", in.localEmbeddingOk());
        dims.put("corpusOk", in.corpusOk());
        n.put("mode", result.mode());
        n.put("retrievalBackend", result.backend());
        n.put("degradeReason", result.degradeReason());
        n.put("note", "chatModelPresent 为配置级推断（mock 无 Bean；deepseek+密钥且应用已启动则 Bean 存在），"
                + "非 Bean 容器直检；探测零模型调用。");
        return n;
    }

    public static void write(Path file, ObjectNode node, ObjectMapper mapper) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }
}
