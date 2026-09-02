package com.medical.union.sso;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;

/**
 * 接入统一认证时需要的安全组件。
 *
 * <p>统一认证对所有客户端强制 PKCE（S256）。Spring Security 只给公共客户端自动附带 PKCE 参数，
 * 保密客户端（配了 client-secret）默认不发，因此必须显式开启，否则授权请求会被 Keycloak 拒绝。
 */
public final class MedicalSsoSecurity {

    private MedicalSsoSecurity() {
    }

    /** 默认授权端点前缀 {@code /oauth2/authorization} 的 PKCE 解析器。 */
    public static OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(
            ClientRegistrationRepository registrations) {
        return pkceAuthorizationRequestResolver(registrations, "/oauth2/authorization");
    }

    public static OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(
            ClientRegistrationRepository registrations, String authorizationRequestBaseUri) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, authorizationRequestBaseUri);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }
}
