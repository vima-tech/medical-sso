package com.medical.union.portal.admin;

import java.util.List;

/** 机构或科室。机构带下属科室，科室的 departments 为空。 */
public record OrgUnit(
        String id,
        String code,
        String name,
        int memberCount,
        List<OrgUnit> departments) {
}
