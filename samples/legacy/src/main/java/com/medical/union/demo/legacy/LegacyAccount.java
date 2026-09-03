package com.medical.union.demo.legacy;

/**
 * 子系统自己的账号。模拟真实老系统的用户表：有本地密码，
 * 接入统一身份后多一列 personId 用于关联。
 */
public class LegacyAccount {

    private final String username;
    private final String password;
    private final String displayName;
    private final boolean emergency;
    private String personId;

    public LegacyAccount(String username, String password, String displayName, boolean emergency) {
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.emergency = emergency;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 应急账号：即使关闭了普通本地登录，它仍然可用，避免认证平台故障时无人能进。 */
    public boolean isEmergency() {
        return emergency;
    }

    public String getPersonId() {
        return personId;
    }

    void linkTo(String personId) {
        this.personId = personId;
    }

    boolean passwordMatches(String candidate) {
        return password.equals(candidate);
    }
}
