package com.medical.union.sso;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalUserMapperTest {

    private static Map<String, Object> roles(String... names) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("roles", Arrays.asList(names));
        return map;
    }

    @Test
    void mapsMedicalClaimsAndCurrentClientRoles() {
        MedicalSsoProperties properties = new MedicalSsoProperties();
        properties.setClientId("medical-demo");
        MedicalUserMapper mapper = new MedicalUserMapper(properties);

        Map<String, Object> resourceAccess = new HashMap<String, Object>();
        resourceAccess.put("medical-demo", roles("access", "his-user"));
        resourceAccess.put("other-system", roles("other-role"));

        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("sub", "subject-1");
        claims.put("person_id", "P000123");
        claims.put("employee_no", "10086");
        claims.put("preferred_username", "zhangsan");
        claims.put("name", "张三");
        claims.put("org_code", "H001");
        claims.put("dept_code", "D001");
        claims.put("realm_access", roles("doctor"));
        claims.put("resource_access", resourceAccess);

        MedicalUser user = mapper.fromClaims(claims);

        assertThat(user.personId()).isEqualTo("P000123");
        assertThat(user.name()).isEqualTo("张三");
        assertThat(user.realmRoles()).containsExactly("doctor");
        assertThat(user.clientRoles()).containsExactlyInAnyOrder("access", "his-user");
        assertThat(user.clientRoles()).doesNotContain("other-role");
    }

    @Test
    void readsMultipleAssignmentsAndToleratesSingleValueForm() {
        MedicalSsoProperties properties = new MedicalSsoProperties();
        properties.setClientId("medical-demo");

        Map<String, Object> many = new HashMap<String, Object>();
        many.put("sub", "s1");
        many.put("org_code", "H001");
        many.put("dept_code", "D001");
        many.put("org_codes", Arrays.asList("H001", "H002"));
        many.put("dept_codes", Arrays.asList("D001", "D101"));
        MedicalUser user = new MedicalUserMapper(properties).fromClaims(many);
        assertThat(user.organizationCode()).isEqualTo("H001");
        assertThat(user.organizationCodes()).containsExactly("H001", "H002");
        assertThat(user.departmentCodes()).containsExactly("D001", "D101");

        Map<String, Object> single = new HashMap<String, Object>();
        single.put("sub", "s2");
        single.put("org_codes", "H001");
        single.put("dept_codes", "D001");
        MedicalUser one = new MedicalUserMapper(properties).fromClaims(single);
        assertThat(one.organizationCodes()).containsExactly("H001");
        assertThat(one.departmentCodes()).containsExactly("D001");

        MedicalUser none = new MedicalUserMapper(properties)
                .fromClaims(Collections.<String, Object>singletonMap("sub", "s3"));
        assertThat(none.organizationCodes()).isEmpty();
        assertThat(none.departmentCodes()).isEmpty();
    }

    @Test
    void toleratesMissingRoleClaims() {
        MedicalSsoProperties properties = new MedicalSsoProperties();
        properties.setClientId("medical-demo");

        MedicalUser user = new MedicalUserMapper(properties)
                .fromClaims(Collections.<String, Object>singletonMap("sub", "subject-1"));

        assertThat(user.realmRoles()).isEmpty();
        assertThat(user.clientRoles()).isEmpty();
    }
}
