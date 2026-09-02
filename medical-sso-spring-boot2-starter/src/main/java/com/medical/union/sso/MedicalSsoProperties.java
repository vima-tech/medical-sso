package com.medical.union.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("medical.sso")
public class MedicalSsoProperties {

    /** Keycloak 中为当前系统登记的 Client ID。 */
    private String clientId;

    /** Spring Security 中用作 principal name 的 claim。 */
    private String principalClaim = "preferred_username";

    /**
     * 接口服务是否要求 Access Token 的 aud 包含本系统 Client ID。
     *
     * <p>Keycloak 客户端默认 fullScopeAllowed=true，A 系统签发的 Token 会带上用户在 B 系统的角色。
     * 不校验 audience 时，A 的 Token 可以拿去调 B 的接口并通过 B 的角色判断。默认开启。
     */
    private boolean requireAudience = true;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public boolean isRequireAudience() {
        return requireAudience;
    }

    public void setRequireAudience(boolean requireAudience) {
        this.requireAudience = requireAudience;
    }

    public String getPrincipalClaim() {
        return principalClaim;
    }

    public void setPrincipalClaim(String principalClaim) {
        this.principalClaim = principalClaim;
    }
}
