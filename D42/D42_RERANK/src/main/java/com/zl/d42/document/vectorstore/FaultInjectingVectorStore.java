package com.zl.d42.document.vectorstore;

import com.zl.d42.document.vectorstore.FaultInjectionConfiguration;
import com.zl.d42.document.vectorstore.FaultInjectionProperties;
import com.zl.d42.document.vectorstore.SimulatedConnectionLostException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P5（H5/KP3 冻结）：测试门禁装饰器——受控故障注入 VectorStore。
 *
 * <p>装配仅经 {@link FaultInjectionConfiguration}（enabled=true 显式开启，主 profile
 * 永不装配）。所有方法透传真实 {@code pgVectorStore}；仅 add/delete(List) 按调用计数
 * 在冻结的第 N 次抛一次 {@link SimulatedConnectionLostException}（消耗式），
 * 失败点在应用侧客户端边界 ≡ 运行期 PG 连接丢失，PG 本体不动（H5）。</p>
 *
 * <p><b>委托显式按 bean 名注入</b>（{@code @Qualifier("pgVectorStore")}，见配置类）：
 * 装饰器自身成为 VectorStore 类型 @Primary 后，任何按类型延迟解析都会命中装饰器
 * 造成自引用；显式名解析不受 primary 翻转影响，单向依赖无环。</p>
 *
 * <p><b>计数口径</b>：delete 按<b>调用次数</b>（非 id 条数）计数，与 add 对称、矩阵
 * 可复现；分批删除时第 1 批即命中注入点（E3 预期"首批失败→FAILED 落库上抛"）。
 * 计数仅本实例内存态，实例重启归零，注入态与回归态实例分离（熔断#6）。</p>
 */
@Slf4j
public class FaultInjectingVectorStore implements VectorStore {

    private final VectorStore delegate;
    private final FaultInjectionProperties properties;

    private final AtomicInteger addCalls = new AtomicInteger();
    private final AtomicInteger deleteCalls = new AtomicInteger();

    /** 消耗位：命中一次后不再注入（冻结语义：仅抛一次）。 */
    private volatile boolean addFired;
    private volatile boolean deleteFired;

    public FaultInjectingVectorStore(VectorStore delegate,
                                     FaultInjectionProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    @Override
    public void add(List<Document> documents) {
        int n = addCalls.incrementAndGet();
        if (!addFired && properties.getFailAddAttempt() == n) {
            addFired = true;
            log.warn("[fault-injection] SIMULATED_PG_CONNECTION_LOST at add() call #{}", n);
            throw new SimulatedConnectionLostException();
        }
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        int n = deleteCalls.incrementAndGet();
        if (!deleteFired && properties.getFailDeleteAttempt() == n) {
            deleteFired = true;
            log.warn("[fault-injection] SIMULATED_PG_CONNECTION_LOST at delete(List) call #{}", n);
            throw new SimulatedConnectionLostException();
        }
        delegate.delete(idList);
    }

    @Override
    public void delete(String filterExpression) {
        delegate.delete(filterExpression);
    }

    /** Spring AI 1.1.8 VectorStore 抽象方法（Filter AST 删除）——透传，不注入。 */
    @Override
    public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate.similaritySearch(request);
    }
}
