package com.zl.d42.access;

/**
 * 文档权限范围（knowledge_document 三列的显式投影，禁 Map，用户契约）。
 *
 * @param documentId 文档标识
 * @param tenantId   租户标签（NULL=遗留无标签，D3：权限端点不可见）
 * @param deptId     部门标签（可为 '*' 通配）
 * @param deviceId   设备标签（可为 '*' 通配）
 */
public record DocumentScope(String documentId, String tenantId, String deptId, String deviceId) {

    /** 三键齐备才算已打标；任一键缺失=权限空间外（fail-closed，禁止部分标签生效）。 */
    public boolean tagged() {
        return isNotBlank(tenantId) && isNotBlank(deptId) && isNotBlank(deviceId);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
