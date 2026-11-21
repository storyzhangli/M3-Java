package com.zl.d38.rag.assessment;

import com.zl.d38.document.model.DocumentLifecycleStatus;

/**
 * 文档生命周期解析端口：证据评估通过它解析每个文档的生效状态。
 *
 * <p>定义为端口便于在单元测试中替换为可控实现，避免评估服务直接绑定持久化细节。</p>
 */
public interface DocumentLifecyclePort {

    /**
     * 按文档业务标识解析生命周期状态。
     *
     * @param documentId 文档业务标识
     * @return 生命周期状态；Fail-closed：文档不存在返回 NOT_FOUND、查询失败返回
     *         LOOKUP_FAILED、状态无法解析返回 UNKNOWN，均不默认 ACTIVE，实现处需记录
     */
    DocumentLifecycleStatus resolve(String documentId);
}
