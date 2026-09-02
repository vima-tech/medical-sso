package com.medical.union.portal.admin;

import java.util.List;
import java.util.Set;

/** 「人员 × 业务系统」的授权总览。 */
public record AuthorizationMatrix(
        List<SystemOption> systems,
        List<Row> rows) {

    /** 一名人员在各系统上的授权情况。 */
    public record Row(
            String userId,
            String name,
            String employeeNo,
            String username,
            String departmentName,
            boolean enabled,
            Set<String> grantedSystems) {

        public boolean granted(String clientId) {
            return grantedSystems.contains(clientId);
        }
    }
}
