package com.medical.union.portal.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 人员管理。把 Keycloak 的用户、属性和角色映射，翻译成医院能直接理解的字段。
 *
 * <p>三处约定：
 * <ul>
 *   <li>姓名存在用户属性 {@code full_name}，它是子系统 Token 里的 {@code name}。</li>
 *   <li>同时把姓名写进 Keycloak 的 firstName，只为让按姓名搜索可用；不参与任何 Claim。</li>
 *   <li>「能进哪些系统」就是各子系统的 access 角色，界面上不出现这个词。</li>
 * </ul>
 */
public class StaffRegistry {

    /** 内置客户端不是子系统。 */
    private static final List<String> BUILT_IN = List.of(
            "account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console");

    /** 平台自身使用的客户端，不作为可授权的业务系统展示。 */
    private static final List<String> PLATFORM_ONLY = List.of("medical-portal-admin");

    private final KeycloakAdminClient admin;
    private final OrganizationDirectory organizations;

    public StaffRegistry(KeycloakAdminClient admin, OrganizationDirectory organizations) {
        this.admin = admin;
        this.organizations = organizations;
    }

    // ---------- 查询 ----------

    public List<Staff> search(String keyword, int page, int size) {
        Map<String, String> names = organizations.nameByCode();
        List<Staff> result = new ArrayList<>();
        for (Map<String, Object> user : admin.searchUsers(keyword, page * size, size)) {
            result.add(toStaff(user, names, false));
        }
        return result;
    }

    public int count(String keyword) {
        return admin.countUsers(keyword);
    }

    public Staff find(String userId) {
        Map<String, Object> user = admin.getUser(userId);
        return user == null ? null : toStaff(user, organizations.nameByCode(), true);
    }

    private Staff toStaff(Map<String, Object> user, Map<String, String> names, boolean withRoles) {
        String id = String.valueOf(user.get("id"));
        String orgCode = attribute(user, "org_code");
        String deptCode = attribute(user, "dept_code");
        List<String> extraDepts = new ArrayList<>();
        List<String> extraNames = new ArrayList<>();
        for (String code : attributes(user, "dept_codes")) {
            if (!code.equals(deptCode)) {
                extraDepts.add(code);
                extraNames.add(names.getOrDefault(code, code));
            }
        }
        List<String> general = List.of();
        List<String> systems = List.of();
        if (withRoles) {
            general = admin.userRealmRoles(id).stream()
                    .map(r -> String.valueOf(r.get("name")))
                    .filter(n -> !n.startsWith("default-roles"))
                    .collect(Collectors.toList());
            systems = accessibleSystemCodes(id);
        }
        return new Staff(
                id,
                String.valueOf(user.get("username")),
                attribute(user, "full_name"),
                attribute(user, "person_id"),
                attribute(user, "employee_no"),
                orgCode,
                orgCode == null ? null : names.getOrDefault(orgCode, orgCode),
                deptCode,
                deptCode == null ? null : names.getOrDefault(deptCode, deptCode),
                extraDepts,
                extraNames,
                !Boolean.FALSE.equals(user.get("enabled")),
                general,
                systems);
    }

    // ---------- 可选项 ----------

    /** 可分配的通用身份，排除平台内部角色。 */
    public List<RoleOption> generalRoles() {
        List<RoleOption> options = new ArrayList<>();
        for (Map<String, Object> role : admin.realmRoles()) {
            String name = String.valueOf(role.get("name"));
            if (name.startsWith("default-roles") || name.equals("offline_access") || name.equals("uma_authorization")) {
                continue;
            }
            Object description = role.get("description");
            String label = description == null || String.valueOf(description).isBlank()
                    ? name
                    : String.valueOf(description);
            options.add(new RoleOption(String.valueOf(role.get("id")), name, label));
        }
        return options;
    }

    /** 已登记且可授权访问的业务系统。 */
    public List<SystemOption> systems() {
        List<SystemOption> options = new ArrayList<>();
        for (Map<String, Object> client : admin.listClients()) {
            String clientId = String.valueOf(client.get("clientId"));
            if (BUILT_IN.contains(clientId) || PLATFORM_ONLY.contains(clientId)
                    || Boolean.FALSE.equals(client.get("standardFlowEnabled"))) {
                continue;
            }
            String uuid = String.valueOf(client.get("id"));
            String accessRoleId = admin.clientRoles(uuid).stream()
                    .filter(r -> "access".equals(r.get("name")))
                    .map(r -> String.valueOf(r.get("id")))
                    .findFirst()
                    .orElse(null);
            if (accessRoleId == null) {
                continue;   // 没有 access 角色的系统不参与授权
            }
            Object name = client.get("name");
            options.add(new SystemOption(clientId,
                    name == null || String.valueOf(name).isBlank() ? clientId : String.valueOf(name),
                    accessRoleId));
        }
        options.sort((a, b) -> a.name().compareTo(b.name()));
        return options;
    }

    private List<String> accessibleSystemCodes(String userId) {
        List<String> codes = new ArrayList<>();
        for (SystemOption system : systems()) {
            String uuid = clientUuid(system.clientId());
            if (uuid == null) {
                continue;
            }
            boolean granted = admin.userClientRoles(userId, uuid).stream()
                    .anyMatch(r -> "access".equals(r.get("name")));
            if (granted) {
                codes.add(system.clientId());
            }
        }
        return codes;
    }

    private String clientUuid(String clientId) {
        Map<String, Object> client = admin.findClient(clientId);
        return client == null ? null : String.valueOf(client.get("id"));
    }

    // ---------- 写入 ----------

    /** 新增人员，返回新用户 id。 */
    public String create(StaffForm form) {
        ensureUniqueAttribute("person_id", form.getPersonId(), null, "统一人员标识");
        ensureUniqueAttribute("employee_no", form.getEmployeeNo(), null, "工号");
        String userId = admin.createUser(representation(form, new LinkedHashMap<>()));
        if (form.getInitialPassword() != null && !form.getInitialPassword().isBlank()) {
            admin.resetPassword(userId, form.getInitialPassword(), form.isMustChangePassword());
        }
        applyRoles(userId, form);
        applyOrganization(userId, form);
        return userId;
    }

    public void update(StaffForm form) {
        ensureUniqueAttribute("person_id", form.getPersonId(), form.getId(), "统一人员标识");
        ensureUniqueAttribute("employee_no", form.getEmployeeNo(), form.getId(), "工号");
        Map<String, Object> current = admin.getUser(form.getId());
        if (current == null) {
            throw new IllegalArgumentException("人员不存在或已被删除");
        }
        Map<String, Object> representation = representation(form, new LinkedHashMap<>(current));
        representation.remove("username");   // 登录名不允许修改
        admin.updateUser(form.getId(), representation);
        applyRoles(form.getId(), form);
        applyOrganization(form.getId(), form);
    }

    public void resetPassword(String userId, String password, boolean temporary) {
        admin.resetPassword(userId, password, temporary);
    }

    /** 强制某人在所有设备上下线。离职、账号外泄时使用。 */
    public void forceLogout(String userId) {
        admin.logoutUser(userId);
    }

    public void setEnabled(String userId, boolean enabled) {
        Map<String, Object> user = admin.getUser(userId);
        if (user == null) {
            throw new IllegalArgumentException("人员不存在或已被删除");
        }
        user.put("enabled", enabled);
        admin.updateUser(userId, user);
        if (!enabled) {
            admin.logoutUser(userId);   // 停用后立刻断开已有会话
        }
    }

    private Map<String, Object> representation(StaffForm form, Map<String, Object> base) {
        base.put("username", form.getUsername());
        base.put("enabled", form.isEnabled());
        // firstName 只是为了让 Keycloak 的按姓名搜索可用，不参与任何 Claim
        base.put("firstName", form.getName());
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object existing = base.get("attributes");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((k, v) -> attributes.put(String.valueOf(k), v));
        }
        attributes.put("full_name", List.of(form.getName()));
        attributes.put("person_id", List.of(form.getPersonId()));
        attributes.put("employee_no", List.of(form.getEmployeeNo()));
        attributes.put("org_code", List.of(form.getOrganizationCode()));
        attributes.put("dept_code", List.of(form.getDepartmentCode()));
        // 主职 + 兼职的全集，供需要跨机构任职信息的系统使用
        attributes.put("dept_codes", allDepartmentCodes(form));
        attributes.put("org_codes", allOrganizationCodes(form));
        base.put("attributes", attributes);
        return base;
    }

    /** 统一人员标识和工号必须唯一，错绑人员的代价很高。 */
    private void ensureUniqueAttribute(String attribute, String value, String selfId, String label) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (Map<String, Object> user : admin.findUsersByAttribute(attribute, value.trim())) {
            String id = String.valueOf(user.get("id"));
            if (!id.equals(selfId)) {
                throw new IllegalArgumentException(
                        label + " " + value + " 已被 " + user.get("username") + " 占用");
            }
        }
    }

    private void applyRoles(String userId, StaffForm form) {
        // 通用身份
        Map<String, Map<String, Object>> available = new LinkedHashMap<>();
        for (Map<String, Object> role : admin.realmRoles()) {
            available.put(String.valueOf(role.get("name")), role);
        }
        List<Map<String, Object>> current = admin.userRealmRoles(userId).stream()
                .filter(r -> !String.valueOf(r.get("name")).startsWith("default-roles"))
                .collect(Collectors.toList());
        List<String> wanted = form.getGeneralRoles();
        List<Map<String, Object>> toAdd = wanted.stream()
                .filter(available::containsKey)
                .filter(n -> current.stream().noneMatch(r -> n.equals(r.get("name"))))
                .map(available::get)
                .collect(Collectors.toList());
        List<Map<String, Object>> toRemove = current.stream()
                .filter(r -> !wanted.contains(String.valueOf(r.get("name"))))
                .collect(Collectors.toList());
        admin.addRealmRoles(userId, toAdd);
        admin.removeRealmRoles(userId, toRemove);

        // 可访问的系统
        List<String> wantedSystems = form.getAccessibleSystems();
        for (SystemOption system : systems()) {
            String uuid = clientUuid(system.clientId());
            if (uuid == null) {
                continue;
            }
            List<Map<String, Object>> accessRole = admin.clientRoles(uuid).stream()
                    .filter(r -> "access".equals(r.get("name")))
                    .collect(Collectors.toList());
            boolean granted = admin.userClientRoles(userId, uuid).stream()
                    .anyMatch(r -> "access".equals(r.get("name")));
            boolean want = wantedSystems.contains(system.clientId());
            if (want && !granted) {
                admin.addClientRoles(userId, uuid, accessRole);
            } else if (!want && granted) {
                admin.removeClientRoles(userId, uuid, accessRole);
            }
        }
    }

    /**
     * 同步人员的机构科室归属。
     *
     * <p>科室编码写在用户属性里供 Token 下发，同时必须把人加进对应的分组：
     * 机构与科室页面的在职人数、以及删除前的保护判断都按分组成员算，
     * 只写属性不进分组会让那两处失真。
     */
    private void applyOrganization(String userId, StaffForm form) {
        List<String> wantedCodes = allDepartmentCodes(form);
        List<String> targetGroupIds = new ArrayList<>();
        List<String> managedGroupIds = new ArrayList<>();
        for (Map<String, Object> organization : admin.topLevelGroups()) {
            String orgId = String.valueOf(organization.get("id"));
            if (OrganizationRegistry.attribute(organization, OrganizationRegistry.ORG_CODE) == null) {
                continue;
            }
            managedGroupIds.add(orgId);
            for (Map<String, Object> department : admin.childGroups(orgId)) {
                String code = OrganizationRegistry.attribute(department, OrganizationRegistry.DEPT_CODE);
                if (code == null) {
                    continue;
                }
                String departmentId = String.valueOf(department.get("id"));
                managedGroupIds.add(departmentId);
                if (wantedCodes.contains(code)) {
                    targetGroupIds.add(departmentId);
                }
            }
        }

        List<String> current = new ArrayList<>();
        for (Map<String, Object> group : admin.userGroups(userId)) {
            current.add(String.valueOf(group.get("id")));
        }
        // 只动机构科室这棵树里的分组，其他用途的分组不碰
        for (String groupId : current) {
            if (managedGroupIds.contains(groupId) && !targetGroupIds.contains(groupId)) {
                admin.leaveGroup(userId, groupId);
            }
        }
        for (String groupId : targetGroupIds) {
            if (!current.contains(groupId)) {
                admin.joinGroup(userId, groupId);
            }
        }
    }

    /** 主职科室加兼职科室，主职在前且不重复。 */
    private static List<String> allDepartmentCodes(StaffForm form) {
        List<String> codes = new ArrayList<>();
        codes.add(form.getDepartmentCode());
        for (String code : form.getAdditionalDepartmentCodes()) {
            if (code != null && !code.isBlank() && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 全部任职科室所属的机构，去重。 */
    private List<String> allOrganizationCodes(StaffForm form) {
        List<String> codes = new ArrayList<>();
        codes.add(form.getOrganizationCode());
        Map<String, String> departmentToOrganization = new LinkedHashMap<>();
        for (OrganizationOption organization : organizations.organizations()) {
            for (OrganizationOption.DepartmentOption department : organization.departments()) {
                departmentToOrganization.put(department.code(), organization.code());
            }
        }
        for (String deptCode : form.getAdditionalDepartmentCodes()) {
            String orgCode = departmentToOrganization.get(deptCode);
            if (orgCode != null && !codes.contains(orgCode)) {
                codes.add(orgCode);
            }
        }
        return codes;
    }

    private static List<String> attributes(Map<String, Object> user, String key) {
        Object attributes = user.get("attributes");
        if (!(attributes instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object value = map.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = String.valueOf(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String attribute(Map<String, Object> user, String key) {
        Object attributes = user.get("attributes");
        if (!(attributes instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof List<?> values && !values.isEmpty()) {
            String first = String.valueOf(values.get(0));
            return first.isBlank() ? null : first;
        }
        return value == null ? null : String.valueOf(value);
    }
}
