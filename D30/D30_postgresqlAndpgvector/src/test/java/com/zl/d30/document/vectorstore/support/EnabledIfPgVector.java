package com.zl.d30.document.vectorstore.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅当 PostgreSQL/pgvector 可达时启用集成测试；不可达时禁用并给出明确原因。
 *
 * <p>这不是用跳过冒充通过：数据库可达时测试真实执行并断言；不可达时如实标记禁用，
 * 避免在无数据库环境下产生误导性失败。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(PgVectorReachableCondition.class)
public @interface EnabledIfPgVector {
}
