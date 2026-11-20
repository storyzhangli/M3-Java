package d37eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地哈希 Embedding 逐位复刻（评测器内存后端专用）。
 *
 * <p>算法与 D37 生产类 LocalHashingEmbeddingModel / TextFeatureExtractor / FeatureHasher
 * 完全一致：w:/c:/g: 三类特征 → 32 位 FNV-1a → 桶下标 + 符号位 → float 带符号累加 → L2 归一化。
 * 复刻正确性由 SelfTest 的确定性/模长/维度夹具与 PGVECTOR 在线分数交叉核对共同保证。</p>
 */
public final class LocalEmbedding {

    public static final int DIMENSIONS = 384;
    public static final String MODEL = "local-hashing-embedding";
    public static final String MODEL_VERSION = "local-v1";

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private LocalEmbedding() {
    }

    public static float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String feature : extract(text)) {
            vector[bucket(feature)] += sign(feature);
        }
        l2Normalize(vector);
        return vector;
    }

    /** 与生产 TextFeatureExtractor.extract 逐行同构。 */
    public static List<String> extract(String text) {
        List<String> features = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return features;
        }
        String lower = text.toLowerCase();
        int i = 0;
        while (i < lower.length()) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                int start = i;
                while (i < lower.length() && Character.isLetterOrDigit(lower.charAt(i))) {
                    i++;
                }
                features.add("w:" + lower.substring(start, i));
            } else {
                i++;
            }
        }
        char prev = 0;
        for (int j = 0; j < lower.length(); j++) {
            char ch = lower.charAt(j);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            features.add("c:" + ch);
            if (prev != 0) {
                features.add("g:" + prev + ch);
            }
            prev = ch;
        }
        return features;
    }

    public static int hash32(String feature) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < feature.length(); i++) {
            hash ^= feature.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    public static int bucket(String feature) {
        int mod = hash32(feature) % DIMENSIONS;
        return mod < 0 ? mod + DIMENSIONS : mod;
    }

    public static float sign(String feature) {
        return (hash32(feature) & 0x80000000) == 0 ? 1f : -1f;
    }

    private static void l2Normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0.0) {
            return;
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }

    /** 余弦相似度：双方均已 L2 归一化，点积即余弦。double 累加降低求和误差。 */
    public static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    public static boolean isZero(float[] v) {
        for (float x : v) {
            if (x != 0.0f) {
                return false;
            }
        }
        return true;
    }
}
