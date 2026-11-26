package com.zl.d43.document.table.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d43.document.table.po.TableExtractPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 表格抽取记录 Mapper。显式按 documentId+version 删除（D40 教训：
 * 清理必须条件明确，不依赖守卫静默 0 行），查询按页/表序稳定排序。
 */
@Mapper
public interface TableExtractMapper extends BaseMapper<TableExtractPO> {

    @Select("""
            SELECT * FROM d43_table_extract
            WHERE document_id = #{documentId} AND version = #{version}
            ORDER BY page_number, table_index
            """)
    List<TableExtractPO> selectByDocumentVersion(@Param("documentId") String documentId,
                                                 @Param("version") Integer version);

    @Delete("""
            DELETE FROM d43_table_extract
            WHERE document_id = #{documentId} AND version = #{version}
            """)
    int deleteByDocumentVersion(@Param("documentId") String documentId,
                                @Param("version") Integer version);

    @Delete("""
            DELETE FROM d43_table_extract WHERE document_id = #{documentId}
            """)
    int deleteByDocumentId(@Param("documentId") String documentId);

    /** 对账用：文档当前全部表记录（版本不限，ORDER BY 稳定）。 */
    @Select("""
            SELECT * FROM d43_table_extract
            WHERE document_id = #{documentId}
            ORDER BY version, page_number, table_index
            """)
    List<TableExtractPO> selectByDocumentId(@Param("documentId") String documentId);
}
