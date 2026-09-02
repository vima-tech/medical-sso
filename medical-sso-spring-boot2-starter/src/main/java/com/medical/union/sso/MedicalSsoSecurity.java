package com.medical.union.sso;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 接入统一认证时需要的安全组件。
 *
 * <p>统一认证对所有客户端强制 PKCE（S256）。Spring Security 只给公共客户端自动附带 PKCE 参数，
 * 保密客户端（配了 client-secret）默认不发，因此必须显式开启，否则授权请求会被 Keycloak 拒绝。
 *
 * <p>这里自己生成 code_verifier 与 code_challenge，只依赖 Spring Security 5.3 起就稳定的 API，
 * 不使用 5.7 才引入的 {@code OAuth2AuthorizationRequestCustomizers}，以便覆盖更多 Spring Boot 2.x 版本。
 * 换取 Token 时 Spring Security 会自动从授权请求属性里取出 code_verifier 回传。
 */
public final class MedicalSsoSecurity {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

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
        resolver.setAuthorizationRequestCustomizer(pkceCustomizer());
        return resolver;
    }

    private static Consumer<OAuth2AuthorizationRequest.Builder> pkceCustomizer() {
        return new Consumer<OAuth2AuthorizationRequest.Builder>() {
            @Override
            public void accept(OAuth2AuthorizationRequest.Builder builder) {
                final String codeVerifier = newCodeVerifier();
                builder.attributes(new Consumer<Map<String, Object>>() {
                    @Override
                    public void accept(Map<String, Object> attributes) {
                        attributes.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
                    }
                });
                builder.additionalParameters(new Consumer<Map<String, Object>>() {
                    @Override
                    public void accept(Map<String, Object> parameters) {
                        parameters.put(PkceParameterNames.CODE_CHALLENGE, codeChallenge(codeVerifier));
                        parameters.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
                    }
                });
            }
        };
    }

    private static String newCodeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return ENCODER.encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法生成 PKCE code_challenge", ex);
        }
    }
}
