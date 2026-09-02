package com.medical.union.portal.admin;

import java.util.List;

/** 已登记业务系统的展示对象。 */
public record Subsystem(
        String uuid,
        String clientId,
        String name,
        String baseUrl,
        List<String> redirectUris,
        boolean pkceEnforced,
        List<String> roles,
        String stack,
        boolean enabled) {

    /** 除 access 之外由业务系统自己使用的角色。 */
    public List<String> businessRoles() {
        return roles.stream().filter(role -> !"access".equals(role)).toList();
    }
}
