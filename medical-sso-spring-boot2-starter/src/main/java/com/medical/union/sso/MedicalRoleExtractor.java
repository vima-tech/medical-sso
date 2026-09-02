package com.medical.union.sso;

import java.util.Collection;
import java.util.Collections;
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
        if (isBlank(clientId)) {
            return Collections.emptySet();
        }
        Object resourceAccess = claims.get("resource_access");
        if (!(resourceAccess instanceof Map)) {
            return Collections.emptySet();
        }
        return nestedRoles(((Map<?, ?>) resourceAccess).get(clientId));
    }

    private static Set<String> nestedRoles(Object container) {
        if (!(container instanceof Map)) {
            return Collections.emptySet();
        }
        Object roles = ((Map<?, ?>) container).get("roles");
        if (!(roles instanceof Collection)) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<String>();
        for (Object role : (Collection<?>) roles) {
            if (role instanceof String && !isBlank((String) role)) {
                result.add((String) role);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
