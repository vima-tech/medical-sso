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

    private static final DateTimeFormatter STAMP_PATTERN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /**
     * 登录失败原因。Keycloak 给的是 invalid_user_credentials 这类错误码，
     * 直接摊在审计列表里等于让管理员去查 Keycloak 文档。没收录的原样显示，便于发现遗漏。
     */
    private static final Map<String, String> LOGIN_ERRORS = Map.ofEntries(
            Map.entry("invalid_user_credentials", "工号或密码错误"),
            Map.entry("user_not_found", "账号不存在"),
            Map.entry("user_disabled", "账号已停用"),
            Map.entry("user_temporarily_disabled", "因多次失败被临时锁定"),
            Map.entry("account_disabled", "账号已停用"),
            Map.entry("expired_code", "登录页停留过久，需要重新登录"),
            Map.entry("session_expired", "会话已过期"),
            Map.entry("invalid_client_credentials", "业务系统的 Client Secret 不正确"),
            Map.entry("invalid_redirect_uri", "回调地址与登记值不一致"),
            Map.entry("not_allowed", "该账号不被允许登录此系统"));

    private static final Map<String, String> ADMIN_OPERATIONS = Map.of(
            "CREATE", "新增", "UPDATE", "修改", "DELETE", "删除", "ACTION", "操作");

    private static final Map<String, String> ADMIN_TARGETS = Map.ofEntries(
            Map.entry("USER", "人员"),
            Map.entry("GROUP", "机构或科室"),
            Map.entry("GROUP_MEMBERSHIP", "机构科室归属"),
            Map.entry("CLIENT", "业务系统"),
            Map.entry("REALM_ROLE", "通用身份"),
            Map.entry("CLIENT_ROLE", "系统角色"),
            Map.entry("REALM_ROLE_MAPPING", "身份授权"),
            Map.entry("CLIENT_ROLE_MAPPING", "系统授权"),
            Map.entry("REALM", "平台设置"),
            Map.entry("CLIENT_SCOPE", "身份字段范围"),
            Map.entry("PROTOCOL_MAPPER", "身份字段映射"),
            Map.entry("AUTH_FLOW", "登录流程"),
            Map.entry("REALM_SCOPE_MAPPING", "平台范围授权"));

    /**
     * 「登录与退出」默认展示的事件类型。
     *
     * <p>刻意不含 CODE_TO_TOKEN：一次登录必然伴随一次令牌签发，两条并排刷屏，
     * 而后者是协议细节、不是管理员要查的业务事实。需要排查协议层面时，
     * 下拉框里的「含令牌签发」会取消这个过滤，不会丢数据。
     */
    private static final List<String> BUSINESS_LOGIN_TYPES = List.of(
            "LOGIN", "LOGIN_ERROR", "LOGOUT", "UPDATE_PASSWORD", "UPDATE_PASSWORD_ERROR",
            "USER_DISABLED_BY_PERMANENT_LOCKOUT", "USER_DISABLED_BY_TEMPORARY_LOCKOUT");

    /** 下拉里「含令牌签发」的取值，表示不加类型过滤。 */
    public static final String ALL_TYPES = "ALL";

    private final KeycloakAdminClient admin;

    /**
     * 时间戳按平台配置的时区渲染，不用 {@code ZoneId.systemDefault()}：
     * 容器镜像没设 TZ，默认时区是 UTC，管理员看到的时间会比实际早 8 小时。
     */
    private final DateTimeFormatter stamp;

    public AuditCenter(KeycloakAdminClient admin, ZoneId timeZone) {
        this.admin = admin;
        this.stamp = STAMP_PATTERN.withZone(timeZone);
    }

    /** 登录相关记录。 */
    public List<Entry> loginEvents(String type, int max) {
        List<String> types;
        if (ALL_TYPES.equals(type)) {
            types = List.of();
        } else if (type == null || type.isBlank()) {
            types = BUSINESS_LOGIN_TYPES;
        } else {
            types = List.of(type);
        }
        List<Map<String, Object>> raw = admin.loginEvents(types, max);
        if (raw.isEmpty()) {
            return List.of();
        }
        Map<String, String> systemNames = systemNames();
        Map<String, String> userNames = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (Map<String, Object> event : raw) {
            String rawType = String.valueOf(event.get("type"));
            String userId = event.get("userId") == null ? null : String.valueOf(event.get("userId"));
            String who = userId == null ? "-" : userNames.computeIfAbsent(userId, this::displayName);
            Object error = event.get("error");
            // 「登录到哪个系统」是排查时第一个要问的，clientId 要换成登记的系统名称
            Object clientId = event.get("clientId");
            String system = clientId == null
                    ? "-"
                    : systemNames.getOrDefault(String.valueOf(clientId), String.valueOf(clientId));
            entries.add(new Entry(
                    stamp(event.get("time")),
                    LOGIN_EVENTS.getOrDefault(rawType, rawType),
                    who,
                    String.valueOf(event.getOrDefault("ipAddress", "-")),
                    error == null ? "" : describeError(String.valueOf(error)),
                    system));
        }
        return entries;
    }

    /** 管理员在平台上做过的变更。 */
    public List<Entry> adminEvents(int max) {
        List<Entry> entries = new ArrayList<>();
        for (Map<String, Object> event : admin.adminEvents(max)) {
            String operationType = String.valueOf(event.get("operationType"));
            String resource = String.valueOf(event.getOrDefault("resourceType", ""));
            String path = String.valueOf(event.getOrDefault("resourcePath", ""));
            String action = describe(operationType, resource, path);
            Object auth = event.get("authDetails");
            String who = "-";
            if (auth instanceof Map<?, ?> details && details.get("userId") != null) {
                who = displayName(String.valueOf(details.get("userId")));
            }
            entries.add(new Entry(
                    stamp(event.get("time")),
                    action,
                    who,
                    auth instanceof Map<?, ?> details2 && details2.get("ipAddress") != null
                            ? String.valueOf(details2.get("ipAddress")) : "-",
                    String.valueOf(event.getOrDefault("resourcePath", "")),
                    "-"));
        }
        return entries;
    }

    /** 某人当前的在线登录。 */
    public List<Session> sessions(String userId) {
        List<Map<String, Object>> raw = admin.userSessions(userId);
        if (raw.isEmpty()) {
            return List.of();
        }
        // 会话里带的是 clientId（medical-portal 这种技术标识），界面上要显示登记时填的
        // 系统名称。只有确实有会话时才去拉客户端目录，避免为一次空查询多打一次 Admin API。
        Map<String, String> systemNames = systemNames();
        List<Session> sessions = new ArrayList<>();
        for (Map<String, Object> session : raw) {
            Object clients = session.get("clients");
            List<String> systems = new ArrayList<>();
            if (clients instanceof Map<?, ?> map) {
                map.values().forEach(value -> {
                    String clientId = String.valueOf(value);
                    systems.add(systemNames.getOrDefault(clientId, clientId));
                });
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

    /**
     * clientId 到系统名称。名称没填时退回显示 clientId，不至于变成空白。
     *
     * <p>Keycloak 内置客户端的 name 存的是 <code>${client_account-console}</code> 这样的
     * 国际化消息键，不是人能看的名字。直接展示会在审计列表里出现一串 ${...}，
     * 所以这类值一律当作「没填」处理，退回显示 clientId。
     */
    private Map<String, String> systemNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> client : admin.listClients()) {
            Object clientId = client.get("clientId");
            Object name = client.get("name");
            if (clientId == null || name == null) {
                continue;
            }
            String label = String.valueOf(name).trim();
            if (label.isEmpty() || label.startsWith("${")) {
                continue;
            }
            names.put(String.valueOf(clientId), label);
        }
        return names;
    }

    /** 失败原因翻成中文，没收录的原样返回。 */
    private static String describeError(String error) {
        return LOGIN_ERRORS.getOrDefault(error, error);
    }

    /** 把「操作 + 对象」翻译成一句人话，常见动作单独给更贴切的说法。 */
    private static String describe(String operationType, String resource, String path) {
        if ("ACTION".equals(operationType) && path.endsWith("/reset-password")) {
            return "重置密码";
        }
        if ("ACTION".equals(operationType) && path.endsWith("/logout")) {
            return "强制下线";
        }
        String operation = ADMIN_OPERATIONS.getOrDefault(operationType, operationType);
        String target = ADMIN_TARGETS.getOrDefault(resource, resource);
        return operation + target;
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

    private String stamp(Object epochMillis) {
        if (!(epochMillis instanceof Number millis)) {
            return "-";
        }
        return stamp.format(Instant.ofEpochMilli(millis.longValue()));
    }

    /** 一条审计记录。system 只对登录记录有意义，管理操作固定为 "-"。 */
    public record Entry(String time, String action, String who, String ip, String detail, String system) {
    }

    /** 一个在线登录。 */
    public record Session(String id, String startedAt, String lastAccessAt, String ip, List<String> systems) {
    }
}
