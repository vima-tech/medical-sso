package com.medical.union.sso;

import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 统一身份返回的业务对象。
 *
 * <p>访问方法名与 Spring Boot 3 版本的 record 保持一致，两套接入组件的调用代码可以直接互换。
 */
public final class MedicalUser {

    private final String subject;
    private final String personId;
    private final String employeeNo;
    private final String username;
    private final String name;
    private final String organizationCode;
    private final String departmentCode;
    private final List<String> organizationCodes;
    private final List<String> departmentCodes;
    private final Set<String> realmRoles;
    private final Set<String> clientRoles;

    public MedicalUser(
            String subject,
            String personId,
            String employeeNo,
            String username,
            String name,
            String organizationCode,
            String departmentCode,
            List<String> organizationCodes,
            List<String> departmentCodes,
            Set<String> realmRoles,
            Set<String> clientRoles) {
        this.subject = subject;
        this.personId = personId;
        this.employeeNo = employeeNo;
        this.username = username;
        this.name = name;
        this.organizationCode = organizationCode;
        this.departmentCode = departmentCode;
        this.organizationCodes = copyList(organizationCodes);
        this.departmentCodes = copyList(departmentCodes);
        this.realmRoles = copy(realmRoles);
        this.clientRoles = copy(clientRoles);
    }

    private static List<String> copyList(List<String> values) {
        return values == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
    }

    private static Set<String> copy(Set<String> values) {
        return values == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(values));
    }

    public String subject() {
        return subject;
    }

    public String personId() {
        return personId;
    }

    public String employeeNo() {
        return employeeNo;
    }

    public String username() {
        return username;
    }

    public String name() {
        return name;
    }

    public String organizationCode() {
        return organizationCode;
    }

    public String departmentCode() {
        return departmentCode;
    }

    /** 全部任职机构，含主职。 */
    public List<String> organizationCodes() {
        return organizationCodes;
    }

    /** 全部任职科室，含主职。 */
    public List<String> departmentCodes() {
        return departmentCodes;
    }

    public List<String> getOrganizationCodes() {
        return organizationCodes;
    }

    public List<String> getDepartmentCodes() {
        return departmentCodes;
    }

    public Set<String> realmRoles() {
        return realmRoles;
    }

    public Set<String> clientRoles() {
        return clientRoles;
    }

    // Jackson 序列化和老框架的 getter 约定
    public String getSubject() {
        return subject;
    }

    public String getPersonId() {
        return personId;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public String getUsername() {
        return username;
    }

    public String getName() {
        return name;
    }

    public String getOrganizationCode() {
        return organizationCode;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public Set<String> getRealmRoles() {
        return realmRoles;
    }

    public Set<String> getClientRoles() {
        return clientRoles;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MedicalUser)) {
            return false;
        }
        MedicalUser that = (MedicalUser) other;
        return Objects.equals(subject, that.subject)
                && Objects.equals(personId, that.personId)
                && Objects.equals(employeeNo, that.employeeNo)
                && Objects.equals(username, that.username)
                && Objects.equals(name, that.name)
                && Objects.equals(organizationCode, that.organizationCode)
                && Objects.equals(departmentCode, that.departmentCode)
                && organizationCodes.equals(that.organizationCodes)
                && departmentCodes.equals(that.departmentCodes)
                && realmRoles.equals(that.realmRoles)
                && clientRoles.equals(that.clientRoles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, personId, employeeNo, username, name,
                organizationCode, departmentCode, organizationCodes, departmentCodes,
                realmRoles, clientRoles);
    }

    @Override
    public String toString() {
        return "MedicalUser{personId=" + personId + ", username=" + username + ", name=" + name + '}';
    }
}
