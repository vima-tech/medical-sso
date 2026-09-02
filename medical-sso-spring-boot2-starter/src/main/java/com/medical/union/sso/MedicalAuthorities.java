package com.medical.union.sso;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MedicalAuthorities {

    private MedicalAuthorities() {
    }

    static Set<GrantedAuthority> fromClaims(
            Map<String, Object> claims,
            String clientId,
            Collection<? extends GrantedAuthority> existing) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<GrantedAuthority>(existing);
        for (String role : MedicalRoleExtractor.realmRoles(claims)) {
            authorities.add(authority("ROLE_REALM_", role));
        }
        for (String role : MedicalRoleExtractor.clientRoles(claims, clientId)) {
            authorities.add(authority("ROLE_CLIENT_", role));
        }
        return authorities;
    }

    private static GrantedAuthority authority(String prefix, String role) {
        String normalized = role.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return new SimpleGrantedAuthority(prefix + normalized);
    }
}
