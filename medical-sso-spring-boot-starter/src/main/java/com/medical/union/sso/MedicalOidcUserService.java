package com.medical.union.sso;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Set;

public class MedicalOidcUserService extends OidcUserService {

    private final MedicalSsoProperties properties;

    public MedicalOidcUserService(MedicalSsoProperties properties) {
        this.properties = properties;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser user = super.loadUser(userRequest);
        Set<GrantedAuthority> authorities = MedicalAuthorities.fromClaims(
                user.getClaims(), properties.getClientId(), user.getAuthorities());
        String principalClaim = properties.getPrincipalClaim();
        if (user.getUserInfo() == null) {
            return new DefaultOidcUser(authorities, user.getIdToken(), principalClaim);
        }
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), principalClaim);
    }
}

