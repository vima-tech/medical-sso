package com.medical.union.portal.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用身份维护。通用身份是跨系统的岗位标识，例如医生、护士，随 Token 下发给各业务系统。
 *
 * <p>平台自身使用的身份和 Keycloak 内置角色不允许在界面上改动，避免把自己锁在外面。
 */
public class IdentityRegistry {

    /** 平台依赖这个身份判断谁能进管理平台，删掉会没人能管理。 */
    static final String PLATFORM_ADMIN = "sso-platform-admin";

    /** Keycloak 自带角色，不属于业务身份。 */
    private static final Set<String> BUILT_IN = Set.of(
            "offline_access", "uma_authorization");

    private final KeycloakAdminClient admin;

    public IdentityRegistry(KeycloakAdminClient admin) {
        this.admin = admin;
    }

    public List<Identity> list() {
        List<Identity> identities = new ArrayList<>();
        for (Map<String, Object> role : admin.realmRoles()) {
            String name = String.valueOf(role.get("name"));
            if (name.startsWith("default-roles") || BUILT_IN.contains(name)) {
                continue;
            }
            Object description = role.get("description");
            String label = description == null || String.valueOf(description).isBlank()
                    ? name
                    : String.valueOf(description);
            identities.add(new Identity(name, label, admin.realmRoleUserCount(name),
                    PLATFORM_ADMIN.equals(name)));
        }
        identities.sort((a, b) -> a.name().compareTo(b.name()));
        return identities;
    }

    public void create(String name, String label) {
        requireValidName(name);
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("请填写身份名称");
        }
        admin.createRealmRole(name, label.trim());
    }

    public void rename(String name, String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("请填写身份名称");
        }
        Map<String, Object> updated = new LinkedHashMap<>();
        updated.put("name", name);
        updated.put("description", label.trim());
        admin.updateRealmRole(name, updated);
    }

    /** 删除通用身份。仍有人员持有时拒绝，平台自身的身份永不允许删。 */
    public void delete(String name) {
        if (PLATFORM_ADMIN.equals(name)) {
            throw new IllegalArgumentException("平台管理员身份由系统使用，不能删除");
        }
        int holders = admin.realmRoleUserCount(name);
        if (holders > 0) {
            throw new IllegalArgumentException(
                    "还有 " + holders + " 名人员拥有该身份，请先在人员管理里取消后再删除");
        }
        admin.deleteRealmRole(name);
    }

    private static void requireValidName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{1,48}[a-z0-9]")) {
            throw new IllegalArgumentException("身份标识用小写字母、数字和中划线，以字母开头，例如 pharmacist");
        }
    }

    /** 一个通用身份。 */
    public record Identity(String name, String label, int holders, boolean systemManaged) {
    }
}
