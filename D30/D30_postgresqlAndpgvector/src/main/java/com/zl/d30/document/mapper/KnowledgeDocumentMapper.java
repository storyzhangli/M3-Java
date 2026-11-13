package com.zl.d30.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d30.document.po.KnowledgeDocumentPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档表 Mapper。单表 CRUD 由 {@link BaseMapper} 提供，不额外编写 XML。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentPO> {
}
