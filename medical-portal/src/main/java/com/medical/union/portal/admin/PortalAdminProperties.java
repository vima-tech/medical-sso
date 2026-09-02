package com.medical.union.portal.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 子系统登记功能所需配置。Keycloak 地址和 Realm 从 issuer 推导，避免重复配置。
 */
@ConfigurationProperties("portal.admin")
public class PortalAdminProperties {

    /** 关闭后门户不暴露 /admin 区域。 */
    private boolean enabled = true;

    /** 统一认证 issuer，例如 http://localhost:18081/auth/realms/medical。 */
    private String issuerUri;

    /** 具备 manage-clients 权限的服务账号客户端。 */
    private String clientId;

    private String clientSecret;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    /** issuer 去掉 /realms/{realm} 后的 Keycloak 根地址。 */
    public String serverUrl() {
        String issuer = requireIssuer();
        return issuer.substring(0, realmSeparator(issuer));
    }

    public String realm() {
        String issuer = requireIssuer();
        return issuer.substring(realmSeparator(issuer) + "/realms/".length());
    }

    private static int realmSeparator(String issuer) {
        int index = issuer.lastIndexOf("/realms/");
        if (index < 0 || index + "/realms/".length() >= issuer.length()) {
            throw new IllegalStateException("portal.admin.issuer-uri 必须形如 http://host/realms/medical");
        }
        return index;
    }

    private String requireIssuer() {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException("未配置 portal.admin.issuer-uri");
        }
        return issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
    }

}
