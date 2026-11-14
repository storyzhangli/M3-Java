package com.zl.d31.document.vectorindex.support;

import com.zl.d31.document.chunk.model.Chunk;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 稳定 Chunk ID 生成器：基于 RFC 4122 名称型 UUID v5（SHA-1）。
 *
 * <p>名称（name）由稳定身份要素拼接：{@code documentId|version|strategy|
 * chunkIndex|chunkContentHash|splitVersion}，并在 D31 固定命名空间 UUID 下计算。
 * 该设计保证：</p>
 * <ul>
 *   <li>相同输入（含失败重试）恒得相同 UUID，不产生新的无关 ID；</li>
 *   <li>不同文档因 documentId 不同，即使内容相同也得到不同 ID，归属不混淆；</li>
 *   <li>切分算法版本 {@code splitVersion} 变更后旧 ID 不复用；</li>
 *   <li>输出为标准 36 字符 UUID，可直接写入 PgVectorStore 的 uuid 主键。</li>
 * </ul>
 */
@Component
public class StableChunkIdGenerator {

    /** D31 向量索引专用命名空间 UUID（固定常量，不随运行变化）。 */
    public static final UUID NAMESPACE = UUID.fromString("0f9f7d52-6b3e-4a1c-9f2d-7c8e5b3a6d41");

    /**
     * 计算 Chunk 的稳定 ID（36 字符 UUID 字符串）。
     *
     * @param chunk            来源 Chunk
     * @param chunkContentHash Chunk 文本内容指纹
     * @param splitVersion     切分算法版本
     */
    public String generate(Chunk chunk, String chunkContentHash, String splitVersion) {
        String name = String.join("|",
                nullToEmpty(chunk.getDocumentId()),
                String.valueOf(chunk.getVersion()),
                chunk.getStrategy() == null ? "" : chunk.getStrategy().name(),
                String.valueOf(chunk.getChunkIndex()),
                nullToEmpty(chunkContentHash),
                nullToEmpty(splitVersion));
        return nameUuidV5(NAMESPACE, name).toString();
    }

    /**
     * RFC 4122 UUID v5：SHA-1 名称型。
     */
    public static UUID nameUuidV5(UUID namespace, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(toBytes(namespace));
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            byte[] result = new byte[16];
            System.arraycopy(hash, 0, result, 0, 16);
            // 设置版本号 5（高 4 位）与 IETF 变体（10xx）
            result[6] &= 0x0F;
            result[6] |= 0x50;
            result[8] &= 0x3F;
            result[8] |= (byte) 0x80;
            return fromBytes(result);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 为 JDK 内置标准算法，正常环境必然存在
            throw new IllegalStateException("当前环境不支持 SHA-1 摘要算法", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> 8 * (7 - i));
            bytes[8 + i] = (byte) (lsb >>> 8 * (7 - i));
        }
        return bytes;
    }

    private static UUID fromBytes(byte[] bytes) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
