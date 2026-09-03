package com.medical.union.sso;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalUserMapperTest {

    @Test
    void mapsMedicalClaimsAndCurrentClientRoles() {
        MedicalSsoProperties properties = new MedicalSsoProperties();
        properties.setClientId("medical-demo");
        MedicalUserMapper mapper = new MedicalUserMapper(properties);

        MedicalUser user = mapper.fromClaims(Map.of(
                "sub", "subject-1",
                "person_id", "P000123",
                "employee_no", "10086",
                "preferred_username", "zhangsan",
                "name", "张三",
                "org_code", "H001",
                "dept_code", "D001",
                "realm_access", Map.of("roles", List.of("doctor")),
                "resource_access", Map.of(
                        "medical-demo", Map.of("roles", List.of("access", "his-user")),
                        "other-system", Map.of("roles", List.of("other-role")))));

        assertThat(user.personId()).isEqualTo("P000123");
        assertThat(user.realmRoles()).containsExactly("doctor");
        assertThat(user.clientRoles()).containsExactlyInAnyOrder("access", "his-user");
        assertThat(user.clientRoles()).doesNotContain("other-role");
    }

    @Test
    void readsMultipleAssignmentsAndToleratesSingleValueForm() {
        MedicalSsoProperties properties = new MedicalSsoProperties();
        properties.setClientId("medical-demo");

        // 多科室任职：Keycloak 下发数组
        MedicalUser many = new MedicalUserMapper(properties).fromClaims(Map.of(
                "sub", "s1",
                "org_code", "H001",
                "dept_code", "D001",
                "org_codes", List.of("H001", "H002"),
                "dept_codes", List.of("D001", "D101")));
        assertThat(many.organizationCode()).isEqualTo("H001");   // 主职不变
        assertThat(many.organizationCodes()).containsExactly("H001", "H002");
        assertThat(many.departmentCodes()).containsExactly("D001", "D101");

        // 只有一处任职时 Keycloak 会下发字符串而不是数组
        MedicalUser one = new MedicalUserMapper(properties).fromClaims(Map.of(
                "sub", "s2", "org_codes", "H001", "dept_codes", "D001"));
        assertThat(one.organizationCodes()).containsExactly("H001");
        assertThat(one.departmentCodes()).containsExactly("D001");

        // 没有多值字段时不应报错，老 Token 仍要能解析
        MedicalUser none = new MedicalUserMapper(properties).fromClaims(Map.of("sub", "s3"));
        assertThat(none.organizationCodes()).isEmpty();
        assertThat(none.departmentCodes()).isEmpty();
    }

    @Test
    void toleratesMissingRoleClaims() {
        MedicalSsoProperties properties = new MedicalSsoProperties();
        properties.setClientId("medical-demo");

        MedicalUser user = new MedicalUserMapper(properties).fromClaims(Map.of("sub", "subject-1"));

        assertThat(user.realmRoles()).isEmpty();
        assertThat(user.clientRoles()).isEmpty();
    }
}
