package com.medical.union.portal.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子系统登记：把「名称 + 编码 + 访问地址」展开成一个可直接使用的 Keycloak 客户端。
 *
 * <p>回调地址、退出返回地址、Web Origin 全部由访问地址推导，管理员不需要理解 OIDC 细节。
 * 客户端一律强制 PKCE S256，并自动创建 access 角色。
 */
public class SubsystemRegistry {

    /** Spring Security 的固定回调路径，与接入文档中的 registration id 对应。 */
    public static final String REDIRECT_PATH = "/login/oauth2/code/medical-sso";

    /** 登记时选的技术栈存在客户端属性里，重新打开对接文档时才能给回同一套代码。 */
    static final String STACK_ATTRIBUTE = "medical.subsystem.stack";

    /** Keycloak 自带客户端，不作为子系统展示。 */
    private static final Set<String> BUILT_IN = Set.of(
            "account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console");

    private final KeycloakAdminClient admin;

    public SubsystemRegistry(KeycloakAdminClient admin) {
        this.admin = admin;
    }

    public List<Subsystem> list() {
        List<Subsystem> result = new ArrayList<>();
        for (Map<String, Object> client : admin.listClients()) {
            String clientId = String.valueOf(client.get("clientId"));
            if (BUILT_IN.contains(clientId) || !supportsBrowserLogin(client)) {
                continue;   // 服务账号一类的客户端不是子系统
            }
            result.add(toSubsystem(client, false));
        }
        result.sort((left, right) -> left.clientId().compareTo(right.clientId()));
        return result;
    }

    private static boolean supportsBrowserLogin(Map<String, Object> client) {
        return !Boolean.FALSE.equals(client.get("standardFlowEnabled"));
    }

    public Subsystem find(String clientId) {
        Map<String, Object> client = admin.findClient(clientId);
        return client == null ? null : toSubsystem(client, true);
    }

    @SuppressWarnings("unchecked")
    private Subsystem toSubsystem(Map<String, Object> client, boolean withRoles) {
        String uuid = String.valueOf(client.get("id"));
        Map<String, Object> attributes = client.get("attributes") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        List<String> redirectUris = client.get("redirectUris") instanceof List<?> uris
                ? (List<String>) uris
                : List.of();
        Object stack = attributes.get(STACK_ATTRIBUTE);
        return new Subsystem(
                uuid,
                String.valueOf(client.get("clientId")),
                client.get("name") == null ? String.valueOf(client.get("clientId")) : String.valueOf(client.get("name")),
                client.get("baseUrl") == null ? "" : String.valueOf(client.get("baseUrl")),
                redirectUris,
                "S256".equals(attributes.get("pkce.code.challenge.method")),
                withRoles ? admin.clientRoleNames(uuid) : List.of(),
                stack == null ? SubsystemForm.Stack.BOOT2 : String.valueOf(stack),
                !Boolean.FALSE.equals(client.get("enabled")));
    }

    /**
     * 登记一个新子系统，返回它的 Client Secret。Secret 只在登记完成后展示一次。
     */
    public String register(SubsystemForm form) {
        String clientId = form.getCode();
        if (admin.clientExists(clientId)) {
            throw new IllegalArgumentException("系统编码 " + clientId + " 已被占用，请换一个");
        }
        String baseUrl = form.normalizedBaseUrl();
        String uuid = admin.createClient(
                clientRepresentation(clientId, form.getName(), baseUrl, form.getStack()));
        admin.createClientRole(uuid, "access", "允许进入 " + form.getName());
        return admin.clientSecret(uuid);
    }

    /** 修改已登记系统的名称、访问地址和技术栈。系统编码不允许更改。 */
    public void update(SubsystemForm form) {
        Map<String, Object> client = admin.findClient(form.getCode());
        if (client == null) {
            throw new IllegalArgumentException("业务系统不存在或已被删除");
        }
        String baseUrl = form.normalizedBaseUrl();
        Map<String, Object> updated = new LinkedHashMap<>(client);
        updated.put("name", form.getName());
        updated.put("rootUrl", baseUrl);
        updated.put("baseUrl", baseUrl);
        updated.put("redirectUris", List.of(baseUrl + REDIRECT_PATH));
        updated.put("webOrigins", List.of(baseUrl));
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (client.get("attributes") instanceof Map<?, ?> existing) {
            existing.forEach((k, v) -> attributes.put(String.valueOf(k), v));
        }
        attributes.put("pkce.code.challenge.method", "S256");
        attributes.put("post.logout.redirect.uris", baseUrl + "/*");
        attributes.put(STACK_ATTRIBUTE, form.getStack());
        updated.put("attributes", attributes);
        admin.updateClient(String.valueOf(client.get("id")), updated);
    }

    public void setEnabled(String clientId, boolean enabled) {
        Map<String, Object> client = requireClient(clientId);
        Map<String, Object> updated = new LinkedHashMap<>(client);
        updated.put("enabled", enabled);
        admin.updateClient(String.valueOf(client.get("id")), updated);
    }

    /** 删除业务系统。仍有人员被授权时拒绝，避免悄悄回收一批人的权限。 */
    public void delete(String clientId) {
        Map<String, Object> client = requireClient(clientId);
        String uuid = String.valueOf(client.get("id"));
        int granted = admin.usersInClientRole(uuid, "access").size();
        if (granted > 0) {
            throw new IllegalArgumentException(
                    "还有 " + granted + " 名人员拥有该系统的访问权限，请先在授权中心收回后再删除");
        }
        admin.deleteClient(uuid);
    }

    public String regenerateSecret(String clientId) {
        return admin.regenerateClientSecret(String.valueOf(requireClient(clientId).get("id")));
    }

    /** 给业务系统增加一个角色，供该系统自己做粗粒度 RBAC。 */
    public void addRole(String clientId, String roleName, String description) {
        if (roleName == null || !roleName.matches("[a-z][a-z0-9-]{1,48}[a-z0-9]")) {
            throw new IllegalArgumentException("角色标识用小写字母、数字和中划线，以字母开头，例如 outpatient-doctor");
        }
        admin.createClientRole(String.valueOf(requireClient(clientId).get("id")), roleName,
                description == null ? "" : description);
    }

    /** 删除业务系统的角色。access 是平台用来判断能否进入的角色，不允许删。 */
    public void removeRole(String clientId, String roleName) {
        if ("access".equals(roleName)) {
            throw new IllegalArgumentException("access 是平台判断能否进入该系统的角色，不能删除");
        }
        admin.deleteClientRole(String.valueOf(requireClient(clientId).get("id")), roleName);
    }

    private Map<String, Object> requireClient(String clientId) {
        Map<String, Object> client = admin.findClient(clientId);
        if (client == null) {
            throw new IllegalArgumentException("业务系统不存在或已被删除");
        }
        return client;
    }

    private Map<String, Object> clientRepresentation(String clientId, String name, String baseUrl, String stack) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("pkce.code.challenge.method", "S256");
        attributes.put("post.logout.redirect.uris", baseUrl + "/*");
        attributes.put(STACK_ATTRIBUTE, stack);

        Map<String, Object> audienceMapper = new LinkedHashMap<>();
        audienceMapper.put("name", clientId + "-audience");
        audienceMapper.put("protocol", "openid-connect");
        audienceMapper.put("protocolMapper", "oidc-audience-mapper");
        audienceMapper.put("consentRequired", false);
        audienceMapper.put("config", Map.of(
                "included.client.audience", clientId,
                "id.token.claim", "false",
                "access.token.claim", "true",
                "introspection.token.claim", "true"));

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", clientId);
        client.put("name", name);
        client.put("enabled", true);
        client.put("protocol", "openid-connect");
        client.put("publicClient", false);
        client.put("standardFlowEnabled", true);
        client.put("directAccessGrantsEnabled", false);
        client.put("serviceAccountsEnabled", false);
        client.put("frontchannelLogout", true);
        client.put("rootUrl", baseUrl);
        client.put("baseUrl", baseUrl);
        client.put("redirectUris", List.of(baseUrl + REDIRECT_PATH));
        client.put("webOrigins", List.of(baseUrl));
        client.put("attributes", attributes);
        // 只引用本 Realm 实际定义的 Scope。Realm 导入时 clientScopes 数组会替换内置集合，
        // 引用未定义的 Scope 会在导入和创建客户端时报找不到。
        client.put("defaultClientScopes", List.of("profile", "roles", "medical-profile"));
        client.put("protocolMappers", List.of(audienceMapper));
        return client;
    }
}
