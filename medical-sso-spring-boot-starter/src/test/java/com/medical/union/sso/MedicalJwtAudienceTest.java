package com.medical.union.sso;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

/**
 * Keycloak 客户端默认 fullScopeAllowed=true：别的系统拿到的 Token 里同样带着用户在本系统的角色。
 * 只看 resource_access 会把那种 Token 放行，因此必须校验 aud。
 */
class MedicalJwtAudienceTest {

    private static MedicalSsoProperties properties(boolean requireAudience) {
        MedicalSsoProperties properties = new MedicalSsoProperties();
        properties.setClientId("his-api");
        properties.setRequireAudience(requireAudience);
        return properties;
    }

    private static Jwt jwt(List<String> audience) {
        Map<String, Object> roles = new HashMap<String, Object>();
        roles.put("roles", Arrays.asList("access"));
        Map<String, Object> resourceAccess = new HashMap<String, Object>();
        resourceAccess.put("his-api", roles);

        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("sub", "subject-1");
        claims.put("preferred_username", "zhangsan");
        claims.put("resource_access", resourceAccess);
        if (audience != null) {
            claims.put("aud", audience);
        }
        Map<String, Object> headers = Collections.<String, Object>singletonMap("alg", "RS256");
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300), headers, claims);
    }

    @Test
    void acceptsTokenIssuedForThisSystem() {
        AbstractAuthenticationToken token = new MedicalJwtAuthenticationConverter(properties(true))
                .convert(jwt(Arrays.asList("his-api")));

        assertThat(token).isNotNull();
        assertThat(token.getAuthorities())
                .extracting("authority")
                .contains("ROLE_CLIENT_ACCESS");
    }

    @Test
    void rejectsTokenIssuedForAnotherSystem() {
        // 另一个系统的 Token，因为 fullScopeAllowed 照样带着 his-api 的 access 角色
        assertThatThrownBy(() -> new MedicalJwtAuthenticationConverter(properties(true))
                .convert(jwt(Arrays.asList("lis-web"))))
                .isInstanceOf(InvalidBearerTokenException.class)
                .asInstanceOf(throwable(InvalidBearerTokenException.class))
                .extracting(ex -> ex.getError().getDescription())
                .asString()
                .contains("his-api")
                // 该描述会进 WWW-Authenticate 响应头，必须是 ASCII
                .matches("\\p{ASCII}+");
    }

    @Test
    void rejectsTokenWithoutAudience() {
        assertThatThrownBy(() -> new MedicalJwtAuthenticationConverter(properties(true))
                .convert(jwt(null)))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void acceptsSingleStringAudienceAsKeycloakSendsIt() {
        // Keycloak 只有一个受众时 aud 是字符串而不是数组，Jwt.getAudience() 会归一成单元素列表
        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("sub", "subject-1");
        claims.put("preferred_username", "zhangsan");
        claims.put("aud", "his-api");
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Collections.<String, Object>singletonMap("alg", "RS256"), claims);

        assertThat(new MedicalJwtAuthenticationConverter(properties(true)).convert(jwt)).isNotNull();
    }

    @Test
    void rejectsSingleStringAudienceOfAnotherSystem() {
        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("sub", "subject-1");
        claims.put("preferred_username", "zhangsan");
        claims.put("aud", "lis-web");
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Collections.<String, Object>singletonMap("alg", "RS256"), claims);

        assertThatThrownBy(() -> new MedicalJwtAuthenticationConverter(properties(true)).convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void allowsOptingOutForLegacyClientsWithoutAudienceMapper() {
        AbstractAuthenticationToken token = new MedicalJwtAuthenticationConverter(properties(false))
                .convert(jwt(Arrays.asList("lis-web")));

        assertThat(token).isNotNull();
    }
}
