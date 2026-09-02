package com.medical.union.portal.admin;

import java.util.List;

/** 列表和详情里展示的人员。 */
public record Staff(
        String id,
        String username,
        String name,
        String personId,
        String employeeNo,
        String organizationCode,
        String organizationName,
        String departmentCode,
        String departmentName,
        List<String> additionalDepartmentCodes,
        List<String> additionalDepartmentNames,
        boolean enabled,
        List<String> generalRoles,
        List<String> accessibleSystems) {
}
