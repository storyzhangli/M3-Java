package com.zl.d31.document.embedding.local;

/**
 * 特征哈希工具：将字符串特征确定性地映射到固定维度的桶与符号。
 *
 * <p>采用 32 位 FNV-1a 哈希，分布稳定、无外部依赖；同时输出桶下标与符号位，
 * 符号位用于带符号特征哈希（signed feature hashing），降低哈希碰撞带来的系统性偏差。</p>
 */
public final class FeatureHasher {

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;

    private static final int FNV_PRIME = 0x01000193;

    private FeatureHasher() {
    }

    /**
     * 计算特征的 32 位 FNV-1a 哈希。
     */
    public static int hash32(String feature) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < feature.length(); i++) {
            hash ^= feature.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * 哈希到 [0, dimensions) 的桶下标。
     */
    public static int bucket(String feature, int dimensions) {
        int h = hash32(feature);
        int mod = h % dimensions;
        return mod < 0 ? mod + dimensions : mod;
    }

    /**
     * 由哈希符号位得到 +1/-1，实现带符号累加。
     */
    public static float sign(String feature) {
        return (hash32(feature) & 0x80000000) == 0 ? 1f : -1f;
    }
}
