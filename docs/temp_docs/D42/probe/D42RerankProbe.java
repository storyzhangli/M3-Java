import com.alibaba.dashscope.rerank.*;
import java.util.List;

public class D42RerankProbe {
    public static void main(String[] args) {
        String key = System.getenv("DASHSCOPE_API_KEY");
        System.out.println("KEY_LEN=" + (key == null ? "NULL" : key.length()));
        try {
            TextReRank tr = new TextReRank(key, "http", new com.alibaba.dashscope.protocol.ConnectionOptions());
            TextReRankParam p = TextReRankParam.builder()
                    .model("gte-rerank-v2")
                    .query("CNC 冷却系统 E-01 故障怎么处置")
                    .documents(List.of("冷却泵异响伴随流量下降时，优先检查吸入口滤网。", "E-01 报警表示冷却液温度过高，请检查散热器。"))
                    .topN(2)
                    .build();
            TextReRankResult r = tr.call(p);
            System.out.println("STATUS=" + r.getStatusCode());
            System.out.println("CODE=" + r.getCode() + " MSG=" + r.getMessage());
            System.out.println("OUTPUT_NULL=" + (r.getOutput() == null));
            if (r.getOutput() != null && r.getOutput().getResults() != null) {
                r.getOutput().getResults().forEach(x -> System.out.println(
                        "  idx=" + x.getIndex() + " score=" + x.getRelevanceScore()));
            }
            System.out.println("USAGE_REQ=" + (r.getUsage() == null ? "null" : r.getUsage().getTotalTokens()));
            System.out.println("PROBE_OK");
        } catch (Throwable t) {
            System.out.println("PROBE_THROW: " + t.getClass().getName());
            t.printStackTrace(System.out);
            Throwable c = t.getCause();
            int d = 0;
            while (c != null && d++ < 6) {
                System.out.println("CAUSE[" + d + "]: " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
        }
    }
}


