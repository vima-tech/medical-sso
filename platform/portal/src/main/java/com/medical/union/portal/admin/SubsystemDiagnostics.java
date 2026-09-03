package com.medical.union.portal.admin;

import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 业务系统接入自检。
 *
 * <p>开发最常见的卡点不是不会配，而是「配好了却登不进去，看不出卡在哪一步」。
 * 这里把平台侧能自动判断的项目逐条跑一遍，每条给出结论和修复动作，
 * 把排查从翻日志变成看清单。
 */
public class SubsystemDiagnostics {

    private final KeycloakAdminClient admin;
    private final RestClient http;

    public SubsystemDiagnostics(KeycloakAdminClient admin, RestClient.Builder builder) {
        this.admin = admin;
        this.http = builder.build();
    }

    public Report run(String clientId) {
        Map<String, Object> client = admin.findClient(clientId);
        if (client == null) {
            return new Report(clientId, List.of(
                    Check.fail("业务系统存在", "在统一认证中找不到编码为 " + clientId + " 的系统",
                            "请确认系统编码是否填错，或重新登记")));
        }

        List<Check> checks = new ArrayList<>();
        String uuid = String.valueOf(client.get("id"));
        Map<String, Object> attributes = client.get("attributes") instanceof Map<?, ?> map
                ? castAttributes(map)
                : Map.of();

        checks.add(client.get("enabled") == Boolean.FALSE
                ? Check.fail("系统已启用", "该业务系统处于停用状态", "在系统详情页点「启用该系统」")
                : Check.pass("系统已启用", "登录请求会被受理"));

        checks.add(Boolean.FALSE.equals(client.get("standardFlowEnabled"))
                ? Check.fail("允许浏览器登录", "该系统未开启标准登录流程", "重新登记，或联系平台负责人开启 Standard Flow")
                : Check.pass("允许浏览器登录", "支持授权码登录"));

        checks.add("S256".equals(attributes.get("pkce.code.challenge.method"))
                // 管理员看不懂 PKCE，但对接的开发要靠这个词去查配置，两个说法都给
                ? Check.pass("登录防截获保护（PKCE）", "已启用，强度 S256")
                : Check.fail("登录防截获保护（PKCE）", "该系统没有启用这项保护",
                        "平台要求所有系统强制 S256。重新登记可自动设置，或联系平台负责人补上"));

        List<String> redirectUris = client.get("redirectUris") instanceof List<?> list
                ? castStrings(list)
                : List.of();
        String baseUrl = text(client.get("baseUrl"));
        checks.add(redirectUris.isEmpty()
                ? Check.fail("已登记回调地址", "没有登记任何回调地址", "在系统详情页填写正确的系统访问地址")
                : Check.pass("已登记回调地址", String.join("、", redirectUris)));

        // 回调地址与访问地址不一致是最高频的故障：表现为登录后一直跳回登录页
        if (!redirectUris.isEmpty() && baseUrl != null) {
            boolean consistent = redirectUris.stream().anyMatch(uri -> uri.startsWith(baseUrl));
            checks.add(consistent
                    ? Check.pass("回调地址与访问地址一致", "两者同源")
                    : Check.warn("回调地址与访问地址一致",
                            "访问地址是 " + baseUrl + "，回调地址却不在同一个域上",
                            "两者必须完全一致，不能混用 IP 与域名、http 与 https。登录后反复跳回登录页多半是这里"));
        }

        boolean hasAccessRole = admin.clientRoleNames(uuid).contains("access");
        checks.add(hasAccessRole
                ? Check.pass("已建立访问角色", "人员在授权中心勾选后即可进入")
                : Check.fail("已建立访问角色", "该系统缺少 access 角色",
                        "没有这个角色，任何人都无法被授权进入。重新登记可自动创建"));

        List<String> scopes = client.get("defaultClientScopes") instanceof List<?> list
                ? castStrings(list)
                : List.of();
        checks.add(scopes.contains("medical-profile")
                ? Check.pass("下发人员身份字段", "工号、机构、科室会随身份一起下发")
                : Check.fail("下发人员身份字段", "未挂载 medical-profile",
                        "缺少它，子系统拿不到工号、机构和科室，只能拿到登录名"));

        boolean audience = client.get("protocolMappers") instanceof List<?> mappers
                && mappers.stream().anyMatch(mapper -> mapper instanceof Map<?, ?> m
                        && "oidc-audience-mapper".equals(m.get("protocolMapper")));
        checks.add(audience
                ? Check.pass("已配置接收方标识", "接口服务可以校验令牌是否签发给本系统")
                : Check.warn("已配置接收方标识", "缺少 audience 映射",
                        "只做页面登录不受影响；提供接口的系统会因为校验不通过而拒绝所有调用"));

        // 角色不存在时不能去查授权人员，内核会直接 404。
        // 自检页在配置越糟的系统上越不能崩——那正是它要派上用场的时候。
        int granted = hasAccessRole ? admin.usersInClientRole(uuid, "access").size() : 0;
        checks.add(granted > 0
                ? Check.pass("已有授权人员", granted + " 人可以进入该系统")
                : Check.warn("已有授权人员", "还没有人被授权进入",
                        "在授权中心给人员勾选该系统，否则登录后会返回 403"));

        // 平台能不能连上子系统。连不上不一定是错，子系统可能还没部署
        if (baseUrl != null) {
            checks.add(probe(baseUrl));
        }

        return new Report(clientId, checks);
    }

    /** 探测子系统是否可达。只看能否建立连接，不判断返回内容。 */
    private Check probe(String baseUrl) {
        try {
            http.get().uri(baseUrl).retrieve().toBodilessEntity();
            return Check.pass("子系统可达", "平台能访问到 " + baseUrl);
        } catch (RuntimeException ex) {
            // 4xx/5xx 也说明连上了，只有连不上才是问题
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.contains("40") || message.contains("50") || message.contains("30")) {
                return Check.pass("子系统可达", "平台能访问到 " + baseUrl);
            }
            return Check.warn("子系统可达", "平台连不上 " + baseUrl,
                    "子系统还没部署时这是正常的；已部署则检查地址、端口和网络策略");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castAttributes(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static List<String> castStrings(List<?> list) {
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            values.add(String.valueOf(item));
        }
        return values;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    /** 自检结论。 */
    public enum Status { PASS, WARN, FAIL }

    /** 一条自检项。 */
    public record Check(Status status, String item, String detail, String fix) {

        static Check pass(String item, String detail) {
            return new Check(Status.PASS, item, detail, null);
        }

        static Check warn(String item, String detail, String fix) {
            return new Check(Status.WARN, item, detail, fix);
        }

        static Check fail(String item, String detail, String fix) {
            return new Check(Status.FAIL, item, detail, fix);
        }
    }

    /** 一次自检的完整结果。 */
    public record Report(String clientId, List<Check> checks) {

        public long failures() {
            return checks.stream().filter(check -> check.status() == Status.FAIL).count();
        }

        public long warnings() {
            return checks.stream().filter(check -> check.status() == Status.WARN).count();
        }

        public boolean ready() {
            return failures() == 0;
        }

        /**
         * 需要处理的检查项，阻塞在前、提醒在后。
         *
         * <p>一次自检十来项，绝大多数时候全是「通过」。按原顺序平铺，唯一那条要处理的
         * 会被埋在一屏之外——而管理员打开这一页就是为了找它。
         */
        public List<Check> problems() {
            return checks.stream()
                    .filter(check -> check.status() != Status.PASS)
                    .sorted(Comparator.comparing(check -> check.status() == Status.FAIL ? 0 : 1))
                    .toList();
        }

        /** 已通过的检查项。默认收起，需要逐条核对时再展开。 */
        public List<Check> passed() {
            return checks.stream().filter(check -> check.status() == Status.PASS).toList();
        }
    }
}
