package com.medical.union.portal.admin;

import com.medical.union.sso.MedicalRoleExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门户「我的应用」的数据来源。
 *
 * <p>只列出当前登录人有访问权限的业务系统，名称和地址取自登记信息，
 * 因此在管理台登记完一个系统、给人员勾上访问权限后，它会自动出现在门户里，
 * 不需要再改门户的配置或代码。
 */
public class ApplicationDirectory {

    /** Keycloak 自带客户端和平台自身使用的客户端，都不是业务系统。 */
    private static final List<String> HIDDEN = List.of(
            "account", "account-console", "admin-cli", "broker", "realm-management",
            "security-admin-console", "medical-portal", "medical-portal-admin");

    private final KeycloakAdminClient admin;

    public ApplicationDirectory(KeycloakAdminClient admin) {
        this.admin = admin;
    }

    public List<Application> forUser(Map<String, Object> claims) {
        List<Application> applications = new ArrayList<>();
        for (Map<String, Object> client : admin.listClients()) {
            String clientId = String.valueOf(client.get("clientId"));
            if (HIDDEN.contains(clientId) || Boolean.FALSE.equals(client.get("standardFlowEnabled"))) {
                continue;
            }
            if (!MedicalRoleExtractor.clientRoles(claims, clientId).contains("access")) {
                continue;   // 没有该系统访问权限的人看不到它
            }
            String url = text(client.get("baseUrl"));
            if (url == null) {
                url = text(client.get("rootUrl"));
            }
            if (url == null) {
                continue;   // 没登记访问地址就没法跳转
            }
            String name = text(client.get("name"));
            applications.add(new Application(
                    name == null ? clientId : name,
                    text(client.get("description")),
                    url,
                    badge(clientId)));
        }
        applications.sort((a, b) -> a.name().compareTo(b.name()));
        return applications;
    }

    /** 卡片右上角的短标记，取系统编码的前几位。 */
    private static String badge(String clientId) {
        String cleaned = clientId.replace("medical-", "");
        String head = cleaned.length() <= 6 ? cleaned : cleaned.substring(0, 6);
        return head.toUpperCase(java.util.Locale.ROOT);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    public record Application(String name, String description, String url, String badge) {
    }
}
