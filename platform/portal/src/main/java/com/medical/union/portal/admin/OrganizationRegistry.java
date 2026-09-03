package com.medical.union.portal.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构与科室的维护。底层落在 Keycloak 的分组上，机构是一级分组、科室是它的子分组，
 * 编码存在分组属性 org_code / dept_code 里；界面上只出现「机构」「科室」「编码」。
 *
 * <p>分组名统一写成「编码-名称」，这样在 Keycloak 后台直接看也认得出来。
 */
public class OrganizationRegistry {

    static final String ORG_CODE = "org_code";
    static final String DEPT_CODE = "dept_code";

    private final KeycloakAdminClient admin;

    public OrganizationRegistry(KeycloakAdminClient admin) {
        this.admin = admin;
    }

    // ---------- 查询 ----------

    public List<OrgUnit> tree() {
        List<OrgUnit> organizations = new ArrayList<>();
        for (Map<String, Object> group : admin.topLevelGroups()) {
            String code = attribute(group, ORG_CODE);
            if (code == null) {
                continue;
            }
            String id = String.valueOf(group.get("id"));
            List<OrgUnit> departments = new ArrayList<>();
            for (Map<String, Object> child : admin.childGroups(id)) {
                String deptCode = attribute(child, DEPT_CODE);
                if (deptCode != null) {
                    String childId = String.valueOf(child.get("id"));
                    departments.add(new OrgUnit(childId, deptCode, displayName(child),
                            admin.groupMemberCount(childId), List.of()));
                }
            }
            organizations.add(new OrgUnit(id, code, displayName(group),
                    admin.groupMemberCount(id), departments));
        }
        organizations.sort((a, b) -> a.code().compareTo(b.code()));
        return organizations;
    }

    public OrgUnitForm load(String id) {
        Map<String, Object> group = admin.getGroup(id);
        if (group == null) {
            return null;
        }
        boolean department = attribute(group, DEPT_CODE) != null;
        OrgUnitForm form = new OrgUnitForm();
        form.setId(id);
        form.setName(displayName(group));
        form.setCode(attribute(group, department ? DEPT_CODE : ORG_CODE));
        if (department) {
            form.setParentId(parentIdOf(id));
        }
        return form;
    }

    // ---------- 写入 ----------

    public void save(OrgUnitForm form) {
        ensureCodeAvailable(form);
        Map<String, Object> representation = new LinkedHashMap<>();
        representation.put("name", form.getCode() + "-" + form.getName());
        representation.put("attributes", Map.of(
                form.isDepartment() ? DEPT_CODE : ORG_CODE, List.of(form.getCode())));
        if (form.isNew()) {
            if (form.isDepartment()) {
                admin.createChildGroup(form.getParentId(), representation);
            } else {
                admin.createTopLevelGroup(representation);
            }
        } else {
            admin.updateGroup(form.getId(), representation);
        }
    }

    /**
     * 删除机构或科室。下面还挂着科室或人员时拒绝，避免把人员的机构科室归属删空。
     */
    public void delete(String id) {
        Map<String, Object> group = admin.getGroup(id);
        if (group == null) {
            throw new IllegalArgumentException("该机构或科室不存在或已被删除");
        }
        boolean department = attribute(group, DEPT_CODE) != null;
        if (!department && !admin.childGroups(id).isEmpty()) {
            throw new IllegalArgumentException("该机构下还有科室，请先删除或迁移科室");
        }
        int members = admin.groupMemberCount(id);
        if (members > 0) {
            throw new IllegalArgumentException(
                    "该" + (department ? "科室" : "机构") + "下还有 " + members + " 名人员，请先调整这些人员的归属");
        }
        admin.deleteGroup(id);
    }

    /** 机构编码和科室编码都要全局唯一，人员表单和 Token 都按编码认。 */
    private void ensureCodeAvailable(OrgUnitForm form) {
        String code = form.getCode();
        for (OrgUnit organization : tree()) {
            if (organization.code().equals(code) && !organization.id().equals(form.getId())) {
                throw new IllegalArgumentException("编码 " + code + " 已被机构「" + organization.name() + "」占用");
            }
            for (OrgUnit department : organization.departments()) {
                if (department.code().equals(code) && !department.id().equals(form.getId())) {
                    throw new IllegalArgumentException(
                            "编码 " + code + " 已被科室「" + department.name() + "」占用");
                }
            }
        }
    }

    private String parentIdOf(String childId) {
        for (Map<String, Object> group : admin.topLevelGroups()) {
            String id = String.valueOf(group.get("id"));
            for (Map<String, Object> child : admin.childGroups(id)) {
                if (childId.equals(String.valueOf(child.get("id")))) {
                    return id;
                }
            }
        }
        return null;
    }

    /** 分组名是「编码-名称」，展示时去掉编码前缀。 */
    static String displayName(Map<String, Object> group) {
        String name = String.valueOf(group.get("name"));
        int dash = name.indexOf('-');
        return dash > 0 && dash < name.length() - 1 ? name.substring(dash + 1) : name;
    }

    static String attribute(Map<String, Object> group, String key) {
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
