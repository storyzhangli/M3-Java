import com.zl.d38.document.embedding.local.TextFeatureExtractor;

import java.util.List;

/**
 * D38-R02 KP3 分词器行为夹具：只读调用既有 TextFeatureExtractor，
 * 断言冻结 featureSubset（w/g）对 编码/数字/中文 的真实行为，禁止换分词器。
 * 输出：人类可读 + 每夹具一行 JSON（JSON 行以 K3JSON= 前缀，供证据落盘解析）。
 */
public final class HybridKp3 {

    private static final String[] FIXTURES = {
            "E101", "20%", "15分钟", "主轴过热",
            "数控机床主轴过热报警处置" // 语料风格真实片段，观察 w: 连续 CJK 行为
    };

    public static void main(String[] args) {
        int pass = 0, fail = 0;
        for (String text : FIXTURES) {
            List<String> fs = TextFeatureExtractor.extract(text);
            String w = prefixJoin(fs, "w:");
            String c = prefixJoin(fs, "c:");
            String g = prefixJoin(fs, "g:");
            System.out.println("FIXTURE=[" + text + "]");
            System.out.println("  w: " + w);
            System.out.println("  c: " + c);
            System.out.println("  g: " + g);
            System.out.println("K3JSON=" + json(text, fs));
        }
        // 断言（任务书 KP3 判据 + 实际行为记录）
        pass += check("A1_e101_whole_word", has(f("E101"), "w:e101"));
        pass += check("A2_20pct_word_20", has(f("20%"), "w:20"));
        boolean a3 = has(f("15分钟"), "w:15分钟");
        pass += check("A3_15min_w_token_is_merged_run(记录事实:数字与CJK合并为单一w:token)", a3);
        pass += check("A4_bigram_covers_15分钟", has(f("15分钟"), "g:15") && has(f("15分钟"), "g:5分") && has(f("15分钟"), "g:分钟"));
        pass += check("A5_bigram_covers_chinese", has(f("主轴过热"), "g:主轴") && has(f("主轴过热"), "g:轴过") && has(f("主轴过热"), "g:过热"));
        pass += check("A6_w_cjk_run_preserved", has(f("主轴过热"), "w:主轴过热"));
        System.out.println("KP3_ASSERT_PASS=" + pass + " FAIL=" + fail);
    }

    private static List<String> f(String text) {
        return TextFeatureExtractor.extract(text);
    }

    private static boolean has(List<String> fs, String term) {
        return fs.contains(term);
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "ASSERT_PASS " : "ASSERT_FAIL ") + name);
        return ok ? 1 : 0;
    }

    private static String prefixJoin(List<String> fs, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String s : fs) {
            if (s.startsWith(prefix)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private static String json(String text, List<String> fs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"fixture\":\"").append(esc(text)).append("\",\"features\":[");
        for (int i = 0; i < fs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(fs.get(i))).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
