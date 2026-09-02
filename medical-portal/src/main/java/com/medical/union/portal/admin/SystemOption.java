package com.medical.union.portal.admin;

/** 一个可授权访问的子系统。 */
public record SystemOption(String clientId, String name, String accessRoleId) {
}
