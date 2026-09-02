package com.medical.union.portal.admin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计中心。把 Keycloak 的事件翻译成管理员能直接看懂的中文记录。
 *
 * <p>只读展示，不提供删除入口——审计记录能被管理员删掉就失去意义。
 * 保留期由 Realm 的事件配置决定，到期由 Keycloak 自行清理。
 */
public class AuditCenter {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 事件类型到中文说法的映射，没收录的直接显示原值，便于发现遗漏。 */
    private static final Map<String, String> LOGIN_EVENTS = Map.ofEntries(
            Map.entry("LOGIN", "登录成功"),
            Map.entry("LOGIN_ERROR", "登录失败"),
            Map.entry("LOGOUT", "退出登录"),
            Map.entry("UPDATE_PASSWORD", "修改密码"),
            Map.entry("UPDATE_PASSWORD_ERROR", "修改密码失败"),
            Map.entry("CODE_TO_TOKEN", "签发令牌"),
            Map.entry("CODE_TO_TOKEN_ERROR", "签发令牌失败"),
            Map.entry("REFRESH_TOKEN_ERROR", "刷新令牌失败"),
            Map.entry("USER_DISABLED_BY_PERMANENT_LOCKOUT", "因多次失败被永久锁定"),
            Map.entry("USER_DISABLED_BY_TEMPORARY_LOCKOUT", "因多次失败被临时锁定"));

    private static final Map<String, String> ADMIN_OPERATIONS = Map.of(
            "CREATE", "新增", "UPDATE", "修改", "DELETE", "删除", "ACTION", "操作");

    private static final Map<String, String> ADMIN_TARGETS = Map.of(
            "USER", "人员", "GROUP", "机构或科室", "CLIENT", "业务系统",
            "REALM_ROLE", "通用身份", "CLIENT_ROLE", "系统角色",
            "REALM_ROLE_MAPPING", "身份授权", "CLIENT_ROLE_MAPPING", "系统授权");

    private final KeycloakAdminClient admin;

    public AuditCenter(KeycloakAdminClient admin) {
        this.admin = admin;
    }

    /** 登录相关记录。 */
    public List<Entry> loginEvents(String type, int max) {
        Map<String, String> userNames = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (Map<String, Object> event : admin.loginEvents(type, max)) {
            String rawType = String.valueOf(event.get("type"));
            String userId = event.get("userId") == null ? null : String.valueOf(event.get("userId"));
            String who = userId == null ? "-" : userNames.computeIfAbsent(userId, this::displayName);
            Object error = event.get("error");
            entries.add(new Entry(
                    stamp(event.get("time")),
                    LOGIN_EVENTS.getOrDefault(rawType, rawType),
                    who,
                    String.valueOf(event.getOrDefault("ipAddress", "-")),
                    error == null ? "" : String.valueOf(error)));
        }
        return entries;
    }

    /** 管理员在平台上做过的变更。 */
    public List<Entry> adminEvents(int max) {
        List<Entry> entries = new ArrayList<>();
        for (Map<String, Object> event : admin.adminEvents(max)) {
            String operation = ADMIN_OPERATIONS.getOrDefault(
                    String.valueOf(event.get("operationType")), String.valueOf(event.get("operationType")));
            String resource = String.valueOf(event.getOrDefault("resourceType", ""));
            String target = ADMIN_TARGETS.getOrDefault(resource, resource);
            Object auth = event.get("authDetails");
            String who = "-";
            if (auth instanceof Map<?, ?> details && details.get("userId") != null) {
                who = displayName(String.valueOf(details.get("userId")));
            }
            entries.add(new Entry(
                    stamp(event.get("time")),
                    operation + target,
                    who,
                    auth instanceof Map<?, ?> details2 && details2.get("ipAddress") != null
                            ? String.valueOf(details2.get("ipAddress")) : "-",
                    String.valueOf(event.getOrDefault("resourcePath", ""))));
        }
        return entries;
    }

    /** 某人当前的在线登录。 */
    public List<Session> sessions(String userId) {
        List<Session> sessions = new ArrayList<>();
        for (Map<String, Object> session : admin.userSessions(userId)) {
            Object clients = session.get("clients");
            List<String> systems = new ArrayList<>();
            if (clients instanceof Map<?, ?> map) {
                map.values().forEach(value -> systems.add(String.valueOf(value)));
            }
            sessions.add(new Session(
                    String.valueOf(session.get("id")),
                    stamp(session.get("start")),
                    stamp(session.get("lastAccess")),
                    String.valueOf(session.getOrDefault("ipAddress", "-")),
                    systems));
        }
        return sessions;
    }

    private String displayName(String userId) {
        try {
            Map<String, Object> user = admin.getUser(userId);
            if (user == null) {
                return userId;
            }
            Object attributes = user.get("attributes");
            if (attributes instanceof Map<?, ?> map && map.get("full_name") instanceof List<?> values
                    && !values.isEmpty()) {
                return String.valueOf(values.get(0));
            }
            return String.valueOf(user.get("username"));
        } catch (RuntimeException ex) {
            return userId;   // 人员可能已被删除，审计记录仍要能显示
        }
    }

    private static String stamp(Object epochMillis) {
        if (!(epochMillis instanceof Number millis)) {
            return "-";
        }
        return STAMP.format(Instant.ofEpochMilli(millis.longValue()));
    }

    /** 一条审计记录。 */
    public record Entry(String time, String action, String who, String ip, String detail) {
    }

    /** 一个在线登录。 */
    public record Session(String id, String startedAt, String lastAccessAt, String ip, List<String> systems) {
    }
}
