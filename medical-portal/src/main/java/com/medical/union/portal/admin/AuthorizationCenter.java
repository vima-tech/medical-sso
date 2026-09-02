package com.medical.union.portal.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权中心。一屏看清谁能进哪些业务系统，并支持整列（按科室）批量授权或收回。
 *
 * <p>取数按「一个系统一次查询」的方式拿到该系统的全部授权人，
 * 而不是逐人逐系统查，避免人数一多就变成上千次请求。
 */
public class AuthorizationCenter {

    private final KeycloakAdminClient admin;
    private final StaffRegistry staff;
    private final OrganizationDirectory organizations;

    public AuthorizationCenter(KeycloakAdminClient admin, StaffRegistry staff,
                               OrganizationDirectory organizations) {
        this.admin = admin;
        this.staff = staff;
        this.organizations = organizations;
    }

    /**
     * 生成授权总览。
     *
     * @param departmentId 科室分组 id，为空表示全部人员
     */
    public AuthorizationMatrix matrix(String departmentId) {
        List<SystemOption> systems = staff.systems();

        // 每个系统查一次，得到该系统的授权人集合
        Map<String, Set<String>> grantedByClient = new LinkedHashMap<>();
        for (SystemOption system : systems) {
            Map<String, Object> client = admin.findClient(system.clientId());
            Set<String> userIds = new LinkedHashSet<>();
            if (client != null) {
                for (Map<String, Object> user : admin.usersInClientRole(
                        String.valueOf(client.get("id")), "access")) {
                    userIds.add(String.valueOf(user.get("id")));
                }
            }
            grantedByClient.put(system.clientId(), userIds);
        }

        Map<String, String> names = organizations.nameByCode();
        List<Map<String, Object>> people = departmentId == null || departmentId.isBlank()
                ? admin.searchUsers(null, 0, 500)
                : admin.groupMembers(departmentId);

        List<AuthorizationMatrix.Row> rows = new ArrayList<>();
        for (Map<String, Object> person : people) {
            String id = String.valueOf(person.get("id"));
            Set<String> granted = new LinkedHashSet<>();
            for (SystemOption system : systems) {
                if (grantedByClient.get(system.clientId()).contains(id)) {
                    granted.add(system.clientId());
                }
            }
            String deptCode = attribute(person, "dept_code");
            rows.add(new AuthorizationMatrix.Row(
                    id,
                    attribute(person, "full_name"),
                    attribute(person, "employee_no"),
                    String.valueOf(person.get("username")),
                    deptCode == null ? null : names.getOrDefault(deptCode, deptCode),
                    !Boolean.FALSE.equals(person.get("enabled")),
                    granted));
        }
        rows.sort((a, b) -> {
            String left = a.employeeNo() == null ? "" : a.employeeNo();
            String right = b.employeeNo() == null ? "" : b.employeeNo();
            return left.compareTo(right);
        });
        return new AuthorizationMatrix(systems, rows);
    }

    /**
     * 按提交的勾选结果整批调整授权。
     *
     * @param visibleUserIds 本次页面上展示过的人员，只在这个范围内做增删，
     *                       避免把没显示出来的人的权限误删
     * @param granted        勾选结果，形如 userId:clientId
     * @return 实际发生变更的条数
     */
    public int apply(List<String> visibleUserIds, List<String> granted) {
        Set<String> wanted = granted == null ? Set.of() : new LinkedHashSet<>(granted);
        int changed = 0;
        for (SystemOption system : staff.systems()) {
            Map<String, Object> client = admin.findClient(system.clientId());
            if (client == null) {
                continue;
            }
            String uuid = String.valueOf(client.get("id"));
            List<Map<String, Object>> accessRole = admin.clientRoles(uuid).stream()
                    .filter(role -> "access".equals(role.get("name")))
                    .toList();
            if (accessRole.isEmpty()) {
                continue;
            }
            Set<String> current = new LinkedHashSet<>();
            for (Map<String, Object> user : admin.usersInClientRole(uuid, "access")) {
                current.add(String.valueOf(user.get("id")));
            }
            for (String userId : visibleUserIds) {
                boolean want = wanted.contains(userId + ":" + system.clientId());
                boolean has = current.contains(userId);
                if (want && !has) {
                    admin.addClientRoles(userId, uuid, accessRole);
                    changed++;
                } else if (!want && has) {
                    admin.removeClientRoles(userId, uuid, accessRole);
                    changed++;
                }
            }
        }
        return changed;
    }

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
        return null;
    }
}
