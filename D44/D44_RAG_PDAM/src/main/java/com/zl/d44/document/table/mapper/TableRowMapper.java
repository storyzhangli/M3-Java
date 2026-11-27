package com.zl.d44.document.table.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d44.document.table.po.TableRowPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 表格数据行 Mapper：行随表记录级联删除由服务层显式两步完成（弱外键无 DB 级联，
 * 删除范围永远由 table_id 集合界定——H6 夹具域隔离的 SQL 侧保证）。
 */
@Mapper
public interface TableRowMapper extends BaseMapper<TableRowPO> {

    @Select("""
            SELECT * FROM d44_table_row
            WHERE table_id = #{tableId}
            ORDER BY row_index
            """)
    List<TableRowPO> selectByTableId(@Param("tableId") Long tableId);

    @Delete("""
            <script>
            DELETE FROM d44_table_row WHERE table_id IN
            <foreach item="id" collection="tableIds" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteByTableIds(@Param("tableIds") List<Long> tableIds);

    /** 清理核验用：夹具文档域全部行（仅证据脚本走 documentIds 显式列表调用）。 */
    @Delete("""
            <script>
            DELETE FROM d44_table_row r
            WHERE r.table_id IN (SELECT id FROM d44_table_extract e
                WHERE e.document_id IN
                <foreach item="d" collection="documentIds" open="(" separator="," close=")">#{d}</foreach>)
            </script>
            """)
    int deleteByDocumentIds(@Param("documentIds") List<String> documentIds);
}
