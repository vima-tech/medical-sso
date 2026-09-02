package com.medical.union.sso;

import java.util.Map;

public class MedicalUserMapper {

    private final MedicalSsoProperties properties;

    public MedicalUserMapper(MedicalSsoProperties properties) {
        this.properties = properties;
    }

    public MedicalUser fromClaims(Map<String, Object> claims) {
        return new MedicalUser(
                text(claims, "sub"),
                text(claims, "person_id"),
                text(claims, "employee_no"),
                text(claims, "preferred_username"),
                text(claims, "name"),
                text(claims, "org_code"),
                text(claims, "dept_code"),
                texts(claims, "org_codes"),
                texts(claims, "dept_codes"),
                MedicalRoleExtractor.realmRoles(claims),
                MedicalRoleExtractor.clientRoles(claims, properties.getClientId()));
    }

    /** 多值字段：Keycloak 只有一个值时会下发字符串而不是数组，两种都要认。 */
    private static java.util.List<String> texts(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value == null) {
            return java.util.List.of();
        }
        if (value instanceof java.util.Collection<?> items) {
            return items.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .filter(text -> !text.isBlank())
                    .toList();
        }
        String single = String.valueOf(value);
        return single.isBlank() ? java.util.List.of() : java.util.List.of(single);
    }

    private static String text(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : String.valueOf(value);
    }
}

