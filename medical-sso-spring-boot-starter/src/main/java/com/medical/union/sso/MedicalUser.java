package com.medical.union.sso;

import java.util.List;
import java.util.Set;

public record MedicalUser(
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
}

