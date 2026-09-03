package com.medical.union.portal.admin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationCenterTest {

    @Test
    void loadsOnlyTheRequestedPageForAllStaff() {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        StaffRegistry staff = mock(StaffRegistry.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        when(staff.systems()).thenReturn(List.of());
        when(organizations.nameByCode()).thenReturn(Map.of());
        when(admin.countUsers(null)).thenReturn(45);
        when(admin.searchUsers(null, 20, 20)).thenReturn(List.of(person("u21", "0021")));

        AuthorizationMatrix matrix = new AuthorizationCenter(admin, staff, organizations)
                .matrix(null, 1, 20);

        assertThat(matrix.total()).isEqualTo(45);
        assertThat(matrix.page()).isEqualTo(1);
        assertThat(matrix.hasNext()).isTrue();
        assertThat(matrix.rows()).extracting(AuthorizationMatrix.Row::userId).containsExactly("u21");
        verify(admin).searchUsers(null, 20, 20);
    }

    @Test
    void paginatesDepartmentMembersAfterEmployeeNumberSorting() {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        StaffRegistry staff = mock(StaffRegistry.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        when(staff.systems()).thenReturn(List.of());
        when(organizations.nameByCode()).thenReturn(Map.of());
        List<Map<String, Object>> members = new ArrayList<>();
        for (int i = 25; i >= 1; i--) {
            members.add(person("u" + i, String.format("%04d", i)));
        }
        when(admin.groupMembers("dept-1")).thenReturn(members);

        AuthorizationMatrix matrix = new AuthorizationCenter(admin, staff, organizations)
                .matrix("dept-1", 1, 20);

        assertThat(matrix.total()).isEqualTo(25);
        assertThat(matrix.page()).isEqualTo(1);
        assertThat(matrix.hasNext()).isFalse();
        assertThat(matrix.rows()).extracting(AuthorizationMatrix.Row::employeeNo)
                .containsExactly("0021", "0022", "0023", "0024", "0025");
    }

    @Test
    void clampsAPageBeyondTheLastPage() {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        StaffRegistry staff = mock(StaffRegistry.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        when(staff.systems()).thenReturn(List.of());
        when(organizations.nameByCode()).thenReturn(Map.of());
        when(admin.countUsers(null)).thenReturn(21);
        when(admin.searchUsers(null, 20, 20)).thenReturn(List.of(person("u21", "0021")));

        AuthorizationMatrix matrix = new AuthorizationCenter(admin, staff, organizations)
                .matrix(null, 99, 20);

        assertThat(matrix.page()).isEqualTo(1);
        assertThat(matrix.rows()).hasSize(1);
        verify(admin).searchUsers(null, 20, 20);
    }

    private static Map<String, Object> person(String id, String employeeNo) {
        return Map.of(
                "id", id,
                "username", id,
                "enabled", true,
                "attributes", Map.of(
                        "full_name", List.of("测试人员" + employeeNo),
                        "employee_no", List.of(employeeNo)));
    }
}
