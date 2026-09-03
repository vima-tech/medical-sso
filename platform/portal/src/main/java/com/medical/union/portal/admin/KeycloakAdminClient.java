package com.medical.union.portal.admin;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用服务账号访问 Keycloak Admin REST API，只做子系统登记需要的几件事。
 */
public class KeycloakAdminClient {

    private final RestClient http;
    private final PortalAdminProperties properties;

    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    public KeycloakAdminClient(RestClient.Builder builder, PortalAdminProperties properties) {
        this.properties = properties;
        this.http = builder.baseUrl(properties.serverUrl()).build();
    }

    private synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());

        Map<?, ?> response = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("连接认证内核失败：未能取得管理令牌，请检查平台配置");
        }
        cachedToken = String.valueOf(response.get("access_token"));
        long expiresIn = response.get("expires_in") instanceof Number seconds ? seconds.longValue() : 60L;
        // 提前 15 秒过期，避免边界上用到刚失效的令牌
        cachedTokenExpiry = Instant.now().plus(Duration.ofSeconds(Math.max(expiresIn - 15, 5)));
        return cachedToken;
    }

    private RestClient.RequestHeadersSpec<?> get(String uri, Object... vars) {
        return http.get().uri(uri, vars).header("Authorization", "Bearer " + token());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listClients() {
        return paged("/admin/realms/{realm}/clients", properties.realm());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> findClient(String clientId) {
        List<Map<String, Object>> clients = get(
                "/admin/realms/{realm}/clients?clientId={clientId}", properties.realm(), clientId)
                .retrieve()
                .body(List.class);
        return clients == null || clients.isEmpty() ? null : clients.get(0);
    }

    public boolean clientExists(String clientId) {
        return findClient(clientId) != null;
    }

    /** 创建客户端，返回 Keycloak 内部 id。 */
    public String createClient(Map<String, Object> representation) {
        try {
            var response = http.post()
                    .uri("/admin/realms/{realm}/clients", properties.realm())
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
            String location = response.getHeaders().getFirst("Location");
            if (location != null) {
                return location.substring(location.lastIndexOf('/') + 1);
            }
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("创建客户端失败：" + ex.getResponseBodyAsString(), ex);
        }
        Map<String, Object> created = findClient(String.valueOf(representation.get("clientId")));
        if (created == null) {
            throw new IllegalStateException("登记业务系统失败：认证内核未返回新建的系统");
        }
        return String.valueOf(created.get("id"));
    }

    public void createClientRole(String clientUuid, String name, String description) {
        try {
            http.post()
                    .uri("/admin/realms/{realm}/clients/{id}/roles", properties.realm(), clientUuid)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", name, "description", description))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 409) {   // 409 表示角色已存在
                throw new IllegalStateException("创建系统角色失败：" + ex.getResponseBodyAsString(), ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> clientRoleNames(String clientUuid) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> role : clientRoles(clientUuid)) {
            names.add(String.valueOf(role.get("name")));
        }
        return names;
    }

    public String clientSecret(String clientUuid) {
        Map<?, ?> body = get("/admin/realms/{realm}/clients/{id}/client-secret", properties.realm(), clientUuid)
                .retrieve()
                .body(Map.class);
        return body == null ? null : String.valueOf(body.get("value"));
    }

    // ---------- 人员 ----------

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchUsers(String keyword, int first, int max) {
        StringBuilder uri = new StringBuilder("/admin/realms/{realm}/users?first={first}&max={max}");
        List<Object> vars = new ArrayList<>(List.of(properties.realm(), first, max));
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&search={search}");
            vars.add(keyword.trim());
        }
        List<Map<String, Object>> users = get(uri.toString(), vars.toArray())
                .retrieve()
                .body(List.class);
        return users == null ? List.of() : users;
    }

    public int countUsers(String keyword) {
        String uri = "/admin/realms/{realm}/users/count";
        Object[] vars;
        if (keyword != null && !keyword.isBlank()) {
            uri += "?search={search}";
            vars = new Object[]{properties.realm(), keyword.trim()};
        } else {
            vars = new Object[]{properties.realm()};
        }
        Integer count = get(uri, vars).retrieve().body(Integer.class);
        return count == null ? 0 : count;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getUser(String userId) {
        return get("/admin/realms/{realm}/users/{id}", properties.realm(), userId)
                .retrieve()
                .body(Map.class);
    }

    /** 按属性精确查找，用于统一人员标识、工号查重。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findUsersByAttribute(String name, String value) {
        List<Map<String, Object>> users = get(
                "/admin/realms/{realm}/users?q={q}&max=2", properties.realm(), name + ":" + value)
                .retrieve()
                .body(List.class);
        return users == null ? List.of() : users;
    }

    public String createUser(Map<String, Object> representation) {
        try {
            var response = http.post()
                    .uri("/admin/realms/{realm}/users", properties.realm())
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
            String location = response.getHeaders().getFirst("Location");
            if (location != null) {
                return location.substring(location.lastIndexOf('/') + 1);
            }
            throw new IllegalStateException("新增人员失败：认证内核未返回新建的人员");
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("创建人员失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    public void updateUser(String userId, Map<String, Object> representation) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/users/{id}", properties.realm(), userId)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("保存人员失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    public void resetPassword(String userId, String password, boolean temporary) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/users/{id}/reset-password", properties.realm(), userId)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", password, "temporary", temporary))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("重置密码失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    /** 注销该人员当前所有登录会话。 */
    public void logoutUser(String userId) {
        http.post()
                .uri("/admin/realms/{realm}/users/{id}/logout", properties.realm(), userId)
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .toBodilessEntity();
    }

    // ---------- 角色 ----------

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> realmRoles() {
        return paged("/admin/realms/{realm}/roles", properties.realm());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> userRealmRoles(String userId) {
        List<Map<String, Object>> roles = get(
                "/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.realm(), userId)
                .retrieve()
                .body(List.class);
        return roles == null ? List.of() : roles;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> clientRoles(String clientUuid) {
        return paged("/admin/realms/{realm}/clients/{id}/roles", properties.realm(), clientUuid);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> userClientRoles(String userId, String clientUuid) {
        List<Map<String, Object>> roles = get(
                "/admin/realms/{realm}/users/{userId}/role-mappings/clients/{clientId}",
                properties.realm(), userId, clientUuid)
                .retrieve()
                .body(List.class);
        return roles == null ? List.of() : roles;
    }

    public void addRealmRoles(String userId, List<Map<String, Object>> roles) {
        if (roles.isEmpty()) {
            return;
        }
        http.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.realm(), userId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(roles)
                .retrieve()
                .toBodilessEntity();
    }

    public void removeRealmRoles(String userId, List<Map<String, Object>> roles) {
        if (roles.isEmpty()) {
            return;
        }
        http.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.realm(), userId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(roles)
                .retrieve()
                .toBodilessEntity();
    }

    public void addClientRoles(String userId, String clientUuid, List<Map<String, Object>> roles) {
        if (roles.isEmpty()) {
            return;
        }
        http.post()
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/clients/{clientId}",
                        properties.realm(), userId, clientUuid)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(roles)
                .retrieve()
                .toBodilessEntity();
    }

    public void removeClientRoles(String userId, String clientUuid, List<Map<String, Object>> roles) {
        if (roles.isEmpty()) {
            return;
        }
        http.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/clients/{clientId}",
                        properties.realm(), userId, clientUuid)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(roles)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 逐页取完一个列表接口。
     *
     * <p>认证内核的列表接口都有默认页大小，而且**不带任何超限提示**：
     * 子 Group 默认只给 10 条，客户端和角色默认 100 条。不显式分页的话，
     * 一个有 20 个科室的机构只会显示前 10 个，第 11 个之后的科室在界面上
     * 根本不存在，管理员也不会收到任何提示。这里一律取到取完为止。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> paged(String path, Object... args) {
        String separator = path.contains("?") ? "&" : "?";
        List<Map<String, Object>> all = new ArrayList<>();
        int pageSize = 200;
        for (int first = 0; ; first += pageSize) {
            Object[] callArgs = new Object[args.length + 2];
            System.arraycopy(args, 0, callArgs, 0, args.length);
            callArgs[args.length] = first;
            callArgs[args.length + 1] = pageSize;
            List<Map<String, Object>> page = get(
                    path + separator + "first={__first}&max={__max}", callArgs)
                    .retrieve()
                    .body(List.class);
            if (page == null || page.isEmpty()) {
                return all;
            }
            all.addAll(page);
            if (page.size() < pageSize) {
                return all;   // 不满一页说明已经取完
            }
        }
    }

    // ---------- 机构与科室 ----------

    public List<Map<String, Object>> topLevelGroups() {
        return paged("/admin/realms/{realm}/groups?briefRepresentation=false", properties.realm());
    }

    public List<Map<String, Object>> childGroups(String groupId) {
        return paged("/admin/realms/{realm}/groups/{id}/children?briefRepresentation=false",
                properties.realm(), groupId);
    }

    // ---------- 机构与科室的增删改 ----------

    /** 建立一级机构，返回 Group id。 */
    public String createTopLevelGroup(Map<String, Object> representation) {
        return createGroup("/admin/realms/{realm}/groups", representation, properties.realm());
    }

    /** 在机构下建立科室，返回 Group id。 */
    public String createChildGroup(String parentId, Map<String, Object> representation) {
        return createGroup("/admin/realms/{realm}/groups/{id}/children", representation,
                properties.realm(), parentId);
    }

    private String createGroup(String uri, Map<String, Object> representation, Object... vars) {
        try {
            var response = http.post()
                    .uri(uri, vars)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
            String location = response.getHeaders().getFirst("Location");
            if (location != null) {
                return location.substring(location.lastIndexOf('/') + 1);
            }
            throw new IllegalStateException("保存失败：认证内核未返回新建的记录");
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException(readableError(ex), ex);
        }
    }

    public void updateGroup(String groupId, Map<String, Object> representation) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/groups/{id}", properties.realm(), groupId)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException(readableError(ex), ex);
        }
    }

    public void deleteGroup(String groupId) {
        try {
            http.delete()
                    .uri("/admin/realms/{realm}/groups/{id}", properties.realm(), groupId)
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException(readableError(ex), ex);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getGroup(String groupId) {
        return get("/admin/realms/{realm}/groups/{id}", properties.realm(), groupId)
                .retrieve()
                .body(Map.class);
    }

    /** 该机构或科室下直接挂着的人数，用于删除前的保护。 */
    @SuppressWarnings("unchecked")
    public int groupMemberCount(String groupId) {
        List<Map<String, Object>> members = get(
                "/admin/realms/{realm}/groups/{id}/members?max=1000", properties.realm(), groupId)
                .retrieve()
                .body(List.class);
        return members == null ? 0 : members.size();
    }

    /** Keycloak 的报错是英文 JSON，抽出其中的说明给界面用。 */
    private static String readableError(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body.contains("Top level group named") || body.contains("already exists")) {
            return "同名的机构或科室已存在";
        }
        return "保存失败：" + body;
    }

    // ---------- 人员的机构科室归属 ----------

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> userGroups(String userId) {
        List<Map<String, Object>> groups = get(
                "/admin/realms/{realm}/users/{id}/groups", properties.realm(), userId)
                .retrieve()
                .body(List.class);
        return groups == null ? List.of() : groups;
    }

    public void joinGroup(String userId, String groupId) {
        http.put()
                .uri("/admin/realms/{realm}/users/{userId}/groups/{groupId}",
                        properties.realm(), userId, groupId)
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .toBodilessEntity();
    }

    public void leaveGroup(String userId, String groupId) {
        http.delete()
                .uri("/admin/realms/{realm}/users/{userId}/groups/{groupId}",
                        properties.realm(), userId, groupId)
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .toBodilessEntity();
    }

    // ---------- 授权总览 ----------

    /** 持有某个系统角色的全部人员。用它一次拿到一列，避免逐人查询。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> usersInClientRole(String clientUuid, String roleName) {
        List<Map<String, Object>> users = get(
                "/admin/realms/{realm}/clients/{id}/roles/{role}/users?max=2000",
                properties.realm(), clientUuid, roleName)
                .retrieve()
                .body(List.class);
        return users == null ? List.of() : users;
    }

    /** 某个机构或科室下的人员。 */
    public List<Map<String, Object>> groupMembers(String groupId) {
        return paged("/admin/realms/{realm}/groups/{id}/members", properties.realm(), groupId);
    }

    // ---------- 业务系统维护 ----------

    public void updateClient(String clientUuid, Map<String, Object> representation) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/clients/{id}", properties.realm(), clientUuid)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("保存业务系统失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    public void deleteClient(String clientUuid) {
        try {
            http.delete()
                    .uri("/admin/realms/{realm}/clients/{id}", properties.realm(), clientUuid)
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("删除业务系统失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    /** 重新生成并返回新的 Client Secret。 */
    @SuppressWarnings("unchecked")
    public String regenerateClientSecret(String clientUuid) {
        Map<String, Object> body = http.post()
                .uri("/admin/realms/{realm}/clients/{id}/client-secret", properties.realm(), clientUuid)
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .body(Map.class);
        if (body == null || body.get("value") == null) {
            throw new IllegalStateException("重新生成 Secret 失败");
        }
        return String.valueOf(body.get("value"));
    }

    public void deleteClientRole(String clientUuid, String roleName) {
        try {
            http.delete()
                    .uri("/admin/realms/{realm}/clients/{id}/roles/{role}",
                            properties.realm(), clientUuid, roleName)
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("删除系统角色失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    // ---------- 通用身份维护 ----------

    public void createRealmRole(String name, String description) {
        try {
            http.post()
                    .uri("/admin/realms/{realm}/roles", properties.realm())
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", name, "description", description == null ? "" : description))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                throw new IllegalArgumentException("同名的通用身份已存在");
            }
            throw new IllegalStateException("保存通用身份失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    public void updateRealmRole(String name, Map<String, Object> representation) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/roles/{name}", properties.realm(), name)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(representation)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("保存通用身份失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    public void deleteRealmRole(String name) {
        try {
            http.delete()
                    .uri("/admin/realms/{realm}/roles/{name}", properties.realm(), name)
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("删除通用身份失败：" + ex.getResponseBodyAsString(), ex);
        }
    }

    /** 持有某个通用身份的人数，用于删除前的保护。 */
    @SuppressWarnings("unchecked")
    public int realmRoleUserCount(String name) {
        List<Map<String, Object>> users = get(
                "/admin/realms/{realm}/roles/{name}/users?max=1000", properties.realm(), name)
                .retrieve()
                .body(List.class);
        return users == null ? 0 : users.size();
    }

    // ---------- 审计与在线登录 ----------

    @SuppressWarnings("unchecked")
    /**
     * 登录相关事件。types 为空表示不限类型。
     *
     * <p>Keycloak 的事件接口用重复的 type 参数表示「这几类都要」，不是逗号分隔。
     */
    public List<Map<String, Object>> loginEvents(List<String> types, int max) {
        StringBuilder uri = new StringBuilder("/admin/realms/{realm}/events?max={max}");
        List<Object> vars = new ArrayList<>(List.of(properties.realm(), max));
        if (types != null) {
            for (String type : types) {
                if (type != null && !type.isBlank()) {
                    uri.append("&type={type").append(vars.size()).append('}');
                    vars.add(type);
                }
            }
        }
        List<Map<String, Object>> events =
                get(uri.toString(), vars.toArray()).retrieve().body(List.class);
        return events == null ? List.of() : events;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> adminEvents(int max) {
        List<Map<String, Object>> events = get(
                "/admin/realms/{realm}/admin-events?max={max}", properties.realm(), max)
                .retrieve()
                .body(List.class);
        return events == null ? List.of() : events;
    }

    /** 某人当前的在线登录。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> userSessions(String userId) {
        List<Map<String, Object>> sessions = get(
                "/admin/realms/{realm}/users/{id}/sessions", properties.realm(), userId)
                .retrieve()
                .body(List.class);
        return sessions == null ? List.of() : sessions;
    }
}
