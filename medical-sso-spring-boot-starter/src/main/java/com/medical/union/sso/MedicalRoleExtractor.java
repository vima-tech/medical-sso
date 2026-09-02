package com.medical.union.sso;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class MedicalRoleExtractor {

    private MedicalRoleExtractor() {
    }

    public static Set<String> realmRoles(Map<String, Object> claims) {
        return nestedRoles(claims.get("realm_access"));
    }

    public static Set<String> clientRoles(Map<String, Object> claims, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Set.of();
        }
        Object resourceAccess = claims.get("resource_access");
        if (!(resourceAccess instanceof Map<?, ?> resources)) {
            return Set.of();
        }
        return nestedRoles(resources.get(clientId));
    }

    private static Set<String> nestedRoles(Object container) {
        if (!(container instanceof Map<?, ?> values)) {
            return Set.of();
        }
        Object roles = values.get("roles");
        if (!(roles instanceof Collection<?> collection)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object role : collection) {
            if (role instanceof String value && !value.isBlank()) {
                result.add(value);
            }
        }
        return Set.copyOf(result);
    }
}

