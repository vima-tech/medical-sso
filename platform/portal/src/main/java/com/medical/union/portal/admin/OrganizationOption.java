package com.medical.union.portal.admin;

import java.util.List;

/** 机构及其下属科室，供人员表单的下拉使用。 */
public record OrganizationOption(String code, String name, List<DepartmentOption> departments) {

    public record DepartmentOption(String code, String name) {
    }
}
