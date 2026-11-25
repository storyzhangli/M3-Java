package com.zl.d42.sync;

import com.zl.d42.document.vectorstore.FaultInjectingVectorStore;
import com.zl.d42.document.vectorstore.FaultInjectionProperties;
import com.zl.d42.document.vectorstore.SimulatedConnectionLostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C01/C18/KP3：故障注入装饰器单测——消耗式触发、0=禁用零差异、计数口径与全透传。
 */
class FaultInjectingVectorStoreTest {

    /** 记录型 fake：只记调用，不触库（PG 本体不动，H5）。 */
    private static final class RecordingVectorStore implements VectorStore {
        final List<String> calls = new ArrayList<>();

        @Override
        public void add(List<Document> documents) {
            calls.add("add:" + documents.size());
        }

        @Override
        public void delete(List<String> idList) {
            calls.add("deleteIds:" + idList.size());
        }

        @Override
        public void delete(String filterExpression) {
            calls.add("deleteFilter");
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
            calls.add("deleteAst");
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            calls.add("search");
            return List.of();
        }
    }

    private static FaultInjectingVectorStore store(int failAdd, int failDelete,
                                                   RecordingVectorStore fake) {
        FaultInjectionProperties properties = new FaultInjectionProperties();
        properties.setEnabled(true);
        properties.setFailAddAttempt(failAdd);
        properties.setFailDeleteAttempt(failDelete);
        return new FaultInjectingVectorStore(fake, properties);
    }

    @Test
    @DisplayName("fail-add-attempt=2：第 2 次 add 抛受控异常且仅一次，第 3 次透传成功（消耗式）")
    void addFiresOnceAtN() {
        RecordingVectorStore fake = new RecordingVectorStore();
        FaultInjectingVectorStore store = store(2, 0, fake);
        List<Document> one = List.of(new Document("t"));

        store.add(one);
        assertThrows(SimulatedConnectionLostException.class, () -> store.add(one));
        store.add(one);
        assertEquals(List.of("add:1", "add:1"), fake.calls);
    }

    @Test
    @DisplayName("异常 message 哨兵=SIMULATED_PG_CONNECTION_LOST（classify 归因 PG_UNREACHABLE 依赖）")
    void sentinelMessageStable() {
        assertEquals("SIMULATED_PG_CONNECTION_LOST",
                new SimulatedConnectionLostException().getMessage());
    }

    @Test
    @DisplayName("0=禁用：全量透传零抛错——回归态等价直通（C18 零残留口径）")
    void disabledAttemptsPurePassthrough() {
        RecordingVectorStore fake = new RecordingVectorStore();
        FaultInjectingVectorStore store = store(0, 0, fake);
        store.add(List.of(new Document("t"), new Document("u")));
        store.delete(List.of("id1"));
        store.delete("meta.documentId=='x'");
        assertTrue(store.similaritySearch(SearchRequest.builder().query("q").topK(1).build()).isEmpty());

        assertEquals(List.of("add:2", "deleteIds:1", "deleteFilter", "search"), fake.calls);
    }

    @Test
    @DisplayName("add/delete 独立计数；fail-delete-attempt=1 首批删除即失败，add 不受影响")
    void countersIndependent() {
        RecordingVectorStore fake = new RecordingVectorStore();
        FaultInjectingVectorStore store = store(3, 1, fake);
        assertThrows(SimulatedConnectionLostException.class, () -> store.delete(List.of("a")));
        store.add(List.of(new Document("t")));
        store.add(List.of(new Document("t")));
        // delete 注入位已消耗，后续删除透传；add 未到第 3 次不触发
        store.delete(List.of("b"));
        assertEquals(List.of("add:1", "add:1", "deleteIds:1"), fake.calls);
        assertThrows(SimulatedConnectionLostException.class,
                () -> store.add(List.of(new Document("t"))));
        store.add(List.of(new Document("t")));
        assertEquals(3, fake.calls.stream().filter(c -> c.startsWith("add:")).count());
    }
}
