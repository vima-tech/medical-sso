package com.medical.union.demo.legacy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 子系统原有的账号与会话。真实系统里这是数据库加 Redis，这里用内存模拟，
 * 目的是验证桥接模式不需要子系统改动既有的登录态机制。
 */
@Component
public class LegacyAccountStore {

    private final Map<String, LegacyAccount> accounts = new LinkedHashMap<>();
    /** 子系统自己的令牌，与统一认证无关，桥接后仍由它维持登录态 */
    private final Map<String, String> tokens = new LinkedHashMap<>();

    public LegacyAccountStore() {
        // 模拟老系统里已经存在、但还没和统一身份关联的账号
        accounts.put("zs", new LegacyAccount("zs", "old-pass-1", "张三（旧账号）", false));
        accounts.put("ls", new LegacyAccount("ls", "old-pass-2", "李四（旧账号）", false));
        accounts.put("emergency", new LegacyAccount("emergency", "break-glass", "应急账号", true));
    }

    public Optional<LegacyAccount> byUsername(String username) {
        return Optional.ofNullable(accounts.get(username));
    }

    public Optional<LegacyAccount> byPersonId(String personId) {
        if (personId == null) {
            return Optional.empty();
        }
        return accounts.values().stream()
                .filter(account -> personId.equals(account.getPersonId()))
                .findFirst();
    }

    /** 统一人员标识只能绑定一个本地账号，重复绑定要拦下来。 */
    public void link(LegacyAccount account, String personId) {
        byPersonId(personId)
                .filter(existing -> !existing.getUsername().equals(account.getUsername()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "该统一身份已绑定到账号 " + existing.getUsername() + "，请联系管理员处理");
                });
        account.linkTo(personId);
    }

    /** 发一张子系统自己的令牌，与接入前完全相同的机制。 */
    public String issueToken(LegacyAccount account) {
        String token = "legacy-" + java.util.UUID.randomUUID();
        tokens.put(token, account.getUsername());
        return token;
    }

    public Optional<LegacyAccount> byToken(String token) {
        String username = token == null ? null : tokens.get(token);
        return username == null ? Optional.empty() : byUsername(username);
    }

    public void revoke(String token) {
        tokens.remove(token);
    }

    public List<LegacyAccount> all() {
        return new ArrayList<>(accounts.values());
    }
}
