package com.medical.union.portal.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构与科室目录。数据来自 Keycloak 的 Group 树：一级 Group 是机构，子 Group 是科室，
 * 编码放在 Group 属性 org_code / dept_code 上。
 *
 * <p>人员表单据此做成下拉，管理员不需要手敲编码。
 */
public class OrganizationDirectory {

    private final KeycloakAdminClient admin;

    public OrganizationDirectory(KeycloakAdminClient admin) {
        this.admin = admin;
    }

    public List<OrganizationOption> organizations() {
        List<OrganizationOption> result = new ArrayList<>();
        for (Map<String, Object> group : admin.topLevelGroups()) {
            String code = attribute(group, "org_code");
            if (code == null) {
                continue;   // 没有机构编码的 Group 不是机构
            }
            List<OrganizationOption.DepartmentOption> departments = new ArrayList<>();
            for (Map<String, Object> child : admin.childGroups(String.valueOf(group.get("id")))) {
                String deptCode = attribute(child, "dept_code");
                if (deptCode != null) {
                    departments.add(new OrganizationOption.DepartmentOption(deptCode, displayName(child)));
                }
            }
            result.add(new OrganizationOption(code, displayName(group), departments));
        }
        return result;
    }

    /** 编码到名称的映射，列表页用它把编码显示成中文名。 */
    public Map<String, String> nameByCode() {
        Map<String, String> names = new LinkedHashMap<>();
        for (OrganizationOption organization : organizations()) {
            names.put(organization.code(), organization.name());
            for (OrganizationOption.DepartmentOption department : organization.departments()) {
                names.put(department.code(), department.name());
            }
        }
        return names;
    }

    /** Group 名习惯写成「编码-名称」，展示时去掉前缀编码。 */
    private static String displayName(Map<String, Object> group) {
        String name = String.valueOf(group.get("name"));
        int dash = name.indexOf('-');
        return dash > 0 && dash < name.length() - 1 ? name.substring(dash + 1) : name;
    }

    @SuppressWarnings("unchecked")
    private static String attribute(Map<String, Object> group, String key) {
        Object attributes = group.get("attributes");
        if (!(attributes instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof List<?> values && !values.isEmpty()) {
            String first = String.valueOf(values.get(0));
            return first.isBlank() ? null : first;
        }
        return null;
    }
}
