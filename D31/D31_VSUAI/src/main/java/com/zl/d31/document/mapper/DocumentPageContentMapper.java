package com.zl.d31.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d31.document.po.DocumentPageContentPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档页面级提取结果 Mapper。
 *
 * <p>沿用 MyBatis-Plus BaseMapper 范式，不写 XML。</p>
 */
@Mapper
public interface DocumentPageContentMapper extends BaseMapper<DocumentPageContentPO> {
}
