package com.zl.d41.document.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d41.document.sync.po.AdminAuditPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 特权操作审计 Mapper：仅追加写（INSERT）与 BaseMapper 查询，无任何更新/删除方法——
 * 审计只增不改不删（H7；清理属运维域，不在本轮范围）。
 */
@Mapper
public interface AdminAuditMapper extends BaseMapper<AdminAuditPO> {
}
