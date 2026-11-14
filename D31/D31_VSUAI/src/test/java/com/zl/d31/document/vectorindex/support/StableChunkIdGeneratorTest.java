package com.zl.d31.document.vectorindex.support;

import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.chunk.model.ChunkStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 稳定 Chunk ID 生成器单元测试：验证确定性、版本位、跨文档区分与输入敏感性。
 */
class StableChunkIdGeneratorTest {

    private final StableChunkIdGenerator generator = new StableChunkIdGenerator();

    @Test
    void sameInputsProduceSameUuid() {
        Chunk a = chunk("doc-1", 1, 0, "相同内容");
        Chunk b = chunk("doc-1", 1, 0, "相同内容");
        String idA = generator.generate(a, "hash-a", "split-v1");
        String idB = generator.generate(b, "hash-a", "split-v1");
        assertEquals(idA, idB);
        assertDoesNotThrow(() -> UUID.fromString(idA));
    }

    @Test
    void producedUuidIsVersion5() {
        UUID uuid = UUID.fromString(generator.generate(chunk("doc-1", 1, 0, "内容"), "h", "split-v1"));
        assertEquals(5, uuid.version());
        assertEquals(2, uuid.variant());
    }

    @Test
    void differentDocumentsProduceDifferentIdsEvenWithSameContent() {
        Chunk docA = chunk("doc-A", 1, 0, "完全相同内容");
        Chunk docB = chunk("doc-B", 1, 0, "完全相同内容");
        assertNotEquals(generator.generate(docA, "hash", "split-v1"),
                generator.generate(docB, "hash", "split-v1"));
    }

    @Test
    void differentChunkIndexOrContentHashChangesId() {
        String base = generator.generate(chunk("doc-1", 1, 0, "内容"), "hash-1", "split-v1");
        String otherIndex = generator.generate(chunk("doc-1", 1, 1, "内容"), "hash-1", "split-v1");
        String otherHash = generator.generate(chunk("doc-1", 1, 0, "内容"), "hash-2", "split-v1");
        String otherSplit = generator.generate(chunk("doc-1", 1, 0, "内容"), "hash-1", "split-v2");
        assertNotEquals(base, otherIndex);
        assertNotEquals(base, otherHash);
        assertNotEquals(base, otherSplit);
    }

    @Test
    void nameUuidV5IsStableAcrossCalls() {
        UUID first = StableChunkIdGenerator.nameUuidV5(StableChunkIdGenerator.NAMESPACE, "stable-name");
        UUID second = StableChunkIdGenerator.nameUuidV5(StableChunkIdGenerator.NAMESPACE, "stable-name");
        assertEquals(first, second);
    }

    private Chunk chunk(String documentId, int version, int index, String content) {
        return new Chunk(documentId, "标题", version, ChunkStrategy.STRUCTURE,
                index, content, 0, content.length(), new ArrayList<>());
    }
}
