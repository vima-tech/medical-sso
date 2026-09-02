package com.medical.union.portal.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * 人员新增和编辑表单。字段名按医院的说法，不出现 OIDC 术语。
 */
public class StaffForm {

    /** 新增时为空，编辑时是 Keycloak 用户 id。 */
    private String id;

    @NotBlank(message = "请填写登录名")
    @Pattern(regexp = "[a-zA-Z0-9._-]{3,64}", message = "登录名用字母、数字、点、下划线或中划线，3 到 64 位")
    private String username;

    @NotBlank(message = "请填写姓名")
    @Size(max = 64, message = "姓名不超过 64 个字符")
    private String name;

    @NotBlank(message = "请填写统一人员标识")
    @Size(max = 64, message = "统一人员标识不超过 64 个字符")
    private String personId;

    @NotBlank(message = "请填写工号")
    @Size(max = 64, message = "工号不超过 64 个字符")
    private String employeeNo;

    @NotBlank(message = "请选择所属机构")
    private String organizationCode;

    @NotBlank(message = "请选择所属科室")
    private String departmentCode;

    /**
     * 兼职科室，可跨机构。主职仍由上面的机构科室表示，随 Token 的 org_code / dept_code 下发；
     * 主职加兼职的全集随 org_codes / dept_codes 下发，已接入的系统不受影响。
     */
    private List<String> additionalDepartmentCodes = new ArrayList<>();

    private boolean enabled = true;

    /** 只在新增时使用；编辑走单独的重置密码入口。 */
    private String initialPassword;

    /** 首次登录是否强制修改密码。 */
    private boolean mustChangePassword = true;

    private List<String> generalRoles = new ArrayList<>();

    /** 允许进入的子系统编码。 */
    private List<String> accessibleSystems = new ArrayList<>();

    public boolean isNew() {
        return id == null || id.isBlank();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPersonId() {
        return personId;
    }

    public void setPersonId(String personId) {
        this.personId = personId;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public void setEmployeeNo(String employeeNo) {
        this.employeeNo = employeeNo;
    }

    public String getOrganizationCode() {
        return organizationCode;
    }

    public void setOrganizationCode(String organizationCode) {
        this.organizationCode = organizationCode;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInitialPassword() {
        return initialPassword;
    }

    public void setInitialPassword(String initialPassword) {
        this.initialPassword = initialPassword;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public List<String> getAdditionalDepartmentCodes() {
        return additionalDepartmentCodes;
    }

    public void setAdditionalDepartmentCodes(List<String> additionalDepartmentCodes) {
        this.additionalDepartmentCodes =
                additionalDepartmentCodes == null ? new ArrayList<>() : additionalDepartmentCodes;
    }

    public List<String> getGeneralRoles() {
        return generalRoles;
    }

    public void setGeneralRoles(List<String> generalRoles) {
        this.generalRoles = generalRoles == null ? new ArrayList<>() : generalRoles;
    }

    public List<String> getAccessibleSystems() {
        return accessibleSystems;
    }

    public void setAccessibleSystems(List<String> accessibleSystems) {
        this.accessibleSystems = accessibleSystems == null ? new ArrayList<>() : accessibleSystems;
    }
}
