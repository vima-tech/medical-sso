package com.medical.union.sso;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一认证强制 PKCE，保密客户端不显式开启就会被 Keycloak 拒绝。这里锁住该行为。
 */
class MedicalSsoSecurityTest {

    private static final ClientRegistration REGISTRATION = ClientRegistration
            .withRegistrationId("medical-sso")
            .clientId("his-web")
            .clientSecret("secret")                       // 保密客户端
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/login/oauth2/code/medical-sso")
            .scope("openid", "profile")
            .authorizationUri("http://sso/realms/medical/protocol/openid-connect/auth")
            .tokenUri("http://sso/realms/medical/protocol/openid-connect/token")
            .userInfoUri("http://sso/realms/medical/protocol/openid-connect/userinfo")
            .userNameAttributeName("preferred_username")
            .jwkSetUri("http://sso/realms/medical/protocol/openid-connect/certs")
            .build();

    private OAuth2AuthorizationRequest resolve() {
        OAuth2AuthorizationRequestResolver resolver = MedicalSsoSecurity
                .pkceAuthorizationRequestResolver(new InMemoryClientRegistrationRepository(REGISTRATION));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/medical-sso");
        request.setServletPath("/oauth2/authorization/medical-sso");
        return resolver.resolve(request);
    }

    @Test
    void sendsS256PkceParametersForConfidentialClient() {
        OAuth2AuthorizationRequest authorizationRequest = resolve();

        assertThat(authorizationRequest).isNotNull();
        assertThat(authorizationRequest.getAdditionalParameters())
                .containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
        assertThat(authorizationRequest.getAdditionalParameters())
                .containsKey(PkceParameterNames.CODE_CHALLENGE);
        assertThat(authorizationRequest.getAttributes())
                .containsKey(PkceParameterNames.CODE_VERIFIER);
    }


    @Test
    void putsPkceParametersIntoTheAuthorizationRequestUri() {
        OAuth2AuthorizationRequest authorizationRequest = resolve();

        // 只断言 additionalParameters 不够：参数没进最终 URI 时它照样通过，
        // 而 Keycloak 看到的是重定向 URI。这里按浏览器实际拿到的地址断言。
        String uri = authorizationRequest.getAuthorizationRequestUri();
        assertThat(uri).contains("code_challenge_method=S256");
        assertThat(uri).contains("code_challenge="
                + authorizationRequest.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE));
        assertThat(uri).doesNotContain(PkceParameterNames.CODE_VERIFIER + "=");
    }

    @Test
    void codeChallengeIsSha256OfCodeVerifier() throws Exception {
        OAuth2AuthorizationRequest authorizationRequest = resolve();

        String verifier = (String) authorizationRequest.getAttributes().get(PkceParameterNames.CODE_VERIFIER);
        String challenge = (String) authorizationRequest.getAdditionalParameters()
                .get(PkceParameterNames.CODE_CHALLENGE);

        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        assertThat(challenge).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
    }

    @Test
    void generatesNewCodeVerifierPerRequest() {
        assertThat(resolve().getAttributes().get(PkceParameterNames.CODE_VERIFIER))
                .isNotEqualTo(resolve().getAttributes().get(PkceParameterNames.CODE_VERIFIER));
    }
}
