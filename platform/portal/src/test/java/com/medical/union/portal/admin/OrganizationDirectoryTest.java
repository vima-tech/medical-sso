package com.medical.union.portal.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationDirectoryTest {

    private static Map<String, Object> group(String id, String name, String attributeKey, String code) {
        return Map.of("id", id, "name", name,
                "attributes", Map.of(attributeKey, List.of(code)));
    }

    @Test
    void buildsOrganizationTreeAndStripsCodePrefixFromNames() {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        when(admin.topLevelGroups()).thenReturn(List.of(group("g1", "H001-第一人民医院", "org_code", "H001")));
        when(admin.childGroups("g1")).thenReturn(List.of(
                group("g2", "D001-内科", "dept_code", "D001"),
                group("g3", "D002-检验科", "dept_code", "D002")));

        List<OrganizationOption> organizations = new OrganizationDirectory(admin).organizations();

        assertThat(organizations).hasSize(1);
        assertThat(organizations.get(0).code()).isEqualTo("H001");
        // Group 名习惯写成「编码-名称」，界面上只显示名称
        assertThat(organizations.get(0).name()).isEqualTo("第一人民医院");
        assertThat(organizations.get(0).departments())
                .extracting(OrganizationOption.DepartmentOption::name)
                .containsExactly("内科", "检验科");
    }

    @Test
    void ignoresGroupsWithoutCodeAttribute() {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        when(admin.topLevelGroups()).thenReturn(List.of(
                Map.of("id", "g1", "name", "临时分组"),
                group("g2", "H002-第二人民医院", "org_code", "H002")));
        when(admin.childGroups(anyString())).thenReturn(List.of());

        List<OrganizationOption> organizations = new OrganizationDirectory(admin).organizations();

        assertThat(organizations).extracting(OrganizationOption::code).containsExactly("H002");
    }

    @Test
    void mapsCodeToNameForListDisplay() {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        when(admin.topLevelGroups()).thenReturn(List.of(group("g1", "H001-第一人民医院", "org_code", "H001")));
        when(admin.childGroups("g1")).thenReturn(List.of(group("g2", "D001-内科", "dept_code", "D001")));

        Map<String, String> names = new OrganizationDirectory(admin).nameByCode();

        assertThat(names).containsEntry("H001", "第一人民医院").containsEntry("D001", "内科");
    }
}
