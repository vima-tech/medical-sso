package com.medical.union.portal.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaffRegistryTest {

    private KeycloakAdminClient admin;
    private StaffRegistry registry;

    @BeforeEach
    void setUp() {
        admin = mock(KeycloakAdminClient.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        when(organizations.nameByCode()).thenReturn(Map.of("H001", "第一人民医院", "D001", "内科"));
        registry = new StaffRegistry(admin, organizations);
        when(admin.listClients()).thenReturn(List.of());
        when(admin.realmRoles()).thenReturn(List.of());
        when(admin.userRealmRoles(anyString())).thenReturn(List.of());
    }

    private static StaffForm form(String personId, String employeeNo) {
        StaffForm form = new StaffForm();
        form.setUsername("lisi");
        form.setName("李四");
        form.setPersonId(personId);
        form.setEmployeeNo(employeeNo);
        form.setOrganizationCode("H001");
        form.setDepartmentCode("D001");
        form.setEnabled(true);
        return form;
    }

    @Test
    void rejectsDuplicatePersonId() {
        when(admin.findUsersByAttribute("person_id", "P000123"))
                .thenReturn(List.of(Map.of("id", "other", "username", "zhangsan")));

        assertThatThrownBy(() -> registry.create(form("P000123", "20001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("统一人员标识")
                .hasMessageContaining("zhangsan");

        verify(admin, never()).createUser(any());
    }

    @Test
    void rejectsDuplicateEmployeeNo() {
        when(admin.findUsersByAttribute("person_id", "P000456")).thenReturn(List.of());
        when(admin.findUsersByAttribute("employee_no", "10086"))
                .thenReturn(List.of(Map.of("id", "other", "username", "zhangsan")));

        assertThatThrownBy(() -> registry.create(form("P000456", "10086")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工号");
    }

    @Test
    void allowsKeepingOwnPersonIdWhenEditing() {
        StaffForm form = form("P000456", "20001");
        form.setId("self");
        when(admin.findUsersByAttribute("person_id", "P000456"))
                .thenReturn(List.of(Map.of("id", "self", "username", "lisi")));
        when(admin.findUsersByAttribute("employee_no", "20001"))
                .thenReturn(List.of(Map.of("id", "self", "username", "lisi")));
        when(admin.getUser("self")).thenReturn(new java.util.LinkedHashMap<>(Map.of("id", "self")));

        registry.update(form);

        verify(admin).updateUser(eq("self"), any());
    }

    @Test
    void writesMedicalAttributesAndMirrorsNameForSearch() {
        when(admin.findUsersByAttribute(anyString(), anyString())).thenReturn(List.of());
        when(admin.createUser(any())).thenReturn("new-id");

        registry.create(form("P000456", "20001"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(admin).createUser(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertThat(body).containsEntry("username", "lisi").containsEntry("enabled", true);
        // firstName 只是为了让按姓名搜索可用
        assertThat(body).containsEntry("firstName", "李四");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) body.get("attributes");
        assertThat(attributes).containsEntry("full_name", List.of("李四"));
        assertThat(attributes).containsEntry("person_id", List.of("P000456"));
        assertThat(attributes).containsEntry("employee_no", List.of("20001"));
        assertThat(attributes).containsEntry("org_code", List.of("H001"));
        assertThat(attributes).containsEntry("dept_code", List.of("D001"));
    }

    @Test
    void onlyAddsAndRemovesTheRolesThatActuallyChanged() {
        when(admin.findUsersByAttribute(anyString(), anyString())).thenReturn(List.of());
        when(admin.createUser(any())).thenReturn("new-id");
        Map<String, Object> doctor = Map.of("id", "r1", "name", "doctor");
        Map<String, Object> nurse = Map.of("id", "r2", "name", "nurse");
        when(admin.realmRoles()).thenReturn(List.of(doctor, nurse));
        // 当前已有 doctor，目标只要 nurse
        when(admin.userRealmRoles("new-id")).thenReturn(new ArrayList<>(List.of(doctor)));

        StaffForm form = form("P000456", "20001");
        form.setGeneralRoles(List.of("nurse"));
        registry.create(form);

        verify(admin).addRealmRoles(eq("new-id"), eq(List.of(nurse)));
        verify(admin).removeRealmRoles(eq("new-id"), eq(List.of(doctor)));
    }

    @Test
    void joinsTheDepartmentGroupSoHeadcountAndDeleteGuardStayCorrect() {
        // 只写 dept_code 属性而不进分组，会让机构页面的在职人数和删除保护同时失真
        when(admin.findUsersByAttribute(anyString(), anyString())).thenReturn(List.of());
        when(admin.createUser(any())).thenReturn("new-id");
        when(admin.topLevelGroups()).thenReturn(List.of(
                Map.of("id", "org1", "name", "H001-第一人民医院",
                        "attributes", Map.of("org_code", List.of("H001")))));
        when(admin.childGroups("org1")).thenReturn(List.of(
                Map.of("id", "dep1", "name", "D001-内科",
                        "attributes", Map.of("dept_code", List.of("D001")))));
        when(admin.userGroups("new-id")).thenReturn(List.of());

        registry.create(form("P000456", "20001"));

        verify(admin).joinGroup("new-id", "dep1");
    }

    @Test
    void movingToAnotherDepartmentLeavesTheOldGroup() {
        when(admin.findUsersByAttribute(anyString(), anyString())).thenReturn(List.of());
        when(admin.getUser("u1")).thenReturn(new java.util.LinkedHashMap<>(Map.of("id", "u1")));
        when(admin.topLevelGroups()).thenReturn(List.of(
                Map.of("id", "org1", "name", "H001-第一人民医院",
                        "attributes", Map.of("org_code", List.of("H001")))));
        when(admin.childGroups("org1")).thenReturn(List.of(
                Map.of("id", "dep1", "name", "D001-内科",
                        "attributes", Map.of("dept_code", List.of("D001"))),
                Map.of("id", "dep2", "name", "D002-检验科",
                        "attributes", Map.of("dept_code", List.of("D002")))));
        // 原本在内科
        when(admin.userGroups("u1")).thenReturn(List.of(Map.of("id", "dep1")));

        StaffForm form = form("P000456", "20001");
        form.setId("u1");
        form.setDepartmentCode("D002");   // 调到检验科
        registry.update(form);

        verify(admin).leaveGroup("u1", "dep1");
        verify(admin).joinGroup("u1", "dep2");
    }

    @Test
    void doesNotTouchGroupsOutsideTheOrganizationTree() {
        when(admin.findUsersByAttribute(anyString(), anyString())).thenReturn(List.of());
        when(admin.createUser(any())).thenReturn("new-id");
        when(admin.topLevelGroups()).thenReturn(List.of(
                Map.of("id", "org1", "name", "H001-第一人民医院",
                        "attributes", Map.of("org_code", List.of("H001")))));
        when(admin.childGroups("org1")).thenReturn(List.of(
                Map.of("id", "dep1", "name", "D001-内科",
                        "attributes", Map.of("dept_code", List.of("D001")))));
        // 这人还在一个与机构科室无关的分组里，不应被动到
        when(admin.userGroups("new-id")).thenReturn(List.of(Map.of("id", "other-purpose")));

        registry.create(form("P000456", "20001"));

        verify(admin, never()).leaveGroup("new-id", "other-purpose");
    }

    @Test
    void disablingAlsoTerminatesExistingSessions() {
        when(admin.getUser("u1")).thenReturn(new java.util.LinkedHashMap<>(Map.of("id", "u1", "enabled", true)));

        registry.setEnabled("u1", false);

        verify(admin).updateUser(eq("u1"), any());
        verify(admin).logoutUser("u1");
    }

    @Test
    void enablingDoesNotTerminateSessions() {
        when(admin.getUser("u1")).thenReturn(new java.util.LinkedHashMap<>(Map.of("id", "u1", "enabled", false)));

        registry.setEnabled("u1", true);

        verify(admin, never()).logoutUser(anyString());
    }

    @Test
    void hidesInternalRolesFromTheGeneralIdentityList() {
        when(admin.realmRoles()).thenReturn(List.of(
                Map.of("id", "r1", "name", "default-roles-medical"),
                Map.of("id", "r2", "name", "offline_access"),
                Map.of("id", "r3", "name", "uma_authorization"),
                Map.of("id", "r4", "name", "doctor", "description", "医生")));

        List<RoleOption> options = registry.generalRoles();

        assertThat(options).extracting(RoleOption::name).containsExactly("doctor");
        assertThat(options.get(0).label()).isEqualTo("医生");
    }

    @Test
    void fallsBackToRoleNameWhenNoChineseLabel() {
        when(admin.realmRoles()).thenReturn(List.of(Map.of("id", "r1", "name", "auditor")));

        assertThat(registry.generalRoles().get(0).label()).isEqualTo("auditor");
    }
}
