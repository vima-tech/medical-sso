package com.medical.union.sso;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public class MedicalJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final MedicalSsoProperties properties;
    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    public MedicalJwtAuthenticationConverter(MedicalSsoProperties properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        requireAudience(jwt);
        AbstractAuthenticationToken base = delegate.convert(jwt);
        Collection<GrantedAuthority> existing = base == null ? Set.of() : base.getAuthorities();
        Set<GrantedAuthority> authorities = MedicalAuthorities.fromClaims(
                jwt.getClaims(), properties.getClientId(), existing);
        String principal = jwt.getClaimAsString(properties.getPrincipalClaim());
        return new JwtAuthenticationToken(jwt, authorities, principal == null ? jwt.getSubject() : principal);
    }

    /**
     * 只接受签发给本系统的 Access Token。
     *
     * <p>Keycloak 客户端默认 fullScopeAllowed=true，别的系统拿到的 Token 里同样带着用户在本系统的角色，
     * 只看 resource_access 会把那种 Token 也放行。
     */
    private void requireAudience(Jwt jwt) {
        String clientId = properties.getClientId();
        if (!properties.isRequireAudience() || clientId == null || clientId.isBlank()) {
            return;
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(clientId)) {
            // 描述会进 WWW-Authenticate 响应头。RFC 6750 的字符集不含双引号和反斜杠，
            // 违规时 BearerTokenErrors 会静默回退成 Invalid token，把排查线索丢掉。
            throw new InvalidBearerTokenException(
                    "The access token is not issued for client " + clientId);
        }
    }
}
