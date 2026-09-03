package com.medical.union.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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

    /** 桥接模式的配置。子系统有自己的会话或令牌机制时用它接入。 */
    private final Bridge bridge = new Bridge();

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getPrincipalClaim() {
        return principalClaim;
    }

    public void setPrincipalClaim(String principalClaim) {
        this.principalClaim = principalClaim;
    }

    public boolean isRequireAudience() {
        return requireAudience;
    }

    public void setRequireAudience(boolean requireAudience) {
        this.requireAudience = requireAudience;
    }

    public Bridge getBridge() {
        return bridge;
    }

    /**
     * 桥接模式：由组件承担登录跳转、换码、验签与回调，子系统只实现
     * {@link MedicalIdentityBridge} 决定「拿到身份之后发什么凭证」。
     */
    public static class Bridge {

        /** 开启后组件会注册 /api/auth/sso/** 端点。 */
        private boolean enabled = false;

        /** 统一认证地址，例如 https://sso.intra.example/auth/realms/medical。 */
        private String issuerUri;

        private String clientSecret;

        /**
         * 回调地址，必须与平台上登记的完全一致。
         * 留空时按 {@code <本系统对外地址>/api/auth/sso/callback} 推导。
         */
        private String redirectUri;

        /** 本系统对外访问地址，用于推导回调地址，例如 https://his.intra.example。 */
        private String baseUrl;

        /** 登录成功后回到前端的地址，票据以 ticket 参数附上。 */
        private String successUri = "/sso/callback";

        /** 身份未关联且开启自助绑定时，引导用户去的前端页面。 */
        private String bindUri = "/sso/bind";

        /** 登录失败时回到的前端地址，原因以 reason 参数附上。 */
        private String failureUri = "/login";

        /** 是否允许用旧账号密码自助绑定统一身份。 */
        private boolean selfServiceBinding = true;

        /** 本系统原有的账号密码登录是否仍然可用。 */
        private LocalLogin localLogin = LocalLogin.ENABLED;

        /** 授权请求与绑定票据的有效期。 */
        private Duration stateTtl = Duration.ofMinutes(5);

        public enum LocalLogin {
            /** 与统一认证并存，登录页收在「其他登录方式」里。 */
            ENABLED,
            /** 只留给应急账号，普通人员必须走统一认证。 */
            EMERGENCY_ONLY,
            /** 完全关闭，只能走统一认证。 */
            DISABLED
        }

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

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSuccessUri() {
            return successUri;
        }

        public void setSuccessUri(String successUri) {
            this.successUri = successUri;
        }

        public String getBindUri() {
            return bindUri;
        }

        public void setBindUri(String bindUri) {
            this.bindUri = bindUri;
        }

        public String getFailureUri() {
            return failureUri;
        }

        public void setFailureUri(String failureUri) {
            this.failureUri = failureUri;
        }

        public boolean isSelfServiceBinding() {
            return selfServiceBinding;
        }

        public void setSelfServiceBinding(boolean selfServiceBinding) {
            this.selfServiceBinding = selfServiceBinding;
        }

        public LocalLogin getLocalLogin() {
            return localLogin;
        }

        public void setLocalLogin(LocalLogin localLogin) {
            this.localLogin = localLogin;
        }

        public Duration getStateTtl() {
            return stateTtl;
        }

        public void setStateTtl(Duration stateTtl) {
            this.stateTtl = stateTtl;
        }

        /** 回调地址：显式配置优先，否则按本系统对外地址推导。 */
        public String resolvedRedirectUri() {
            if (redirectUri != null && !redirectUri.trim().isEmpty()) {
                return redirectUri;
            }
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalStateException(
                        "请配置 medical.sso.bridge.redirect-uri 或 medical.sso.bridge.base-url");
            }
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return base + "/api/auth/sso/callback";
        }
    }
}
