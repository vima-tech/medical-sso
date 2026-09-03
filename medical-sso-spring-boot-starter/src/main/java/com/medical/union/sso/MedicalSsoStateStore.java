package com.medical.union.sso;

import java.time.Duration;

/**
 * 登录过程中的一次性凭据存储：授权请求的 state、PKCE 的 code_verifier、绑定票据。
 *
 * <p>默认实现放在进程内存里，够单实例使用。多实例部署时子系统提供自己的实现
 * （通常是 Redis），否则用户可能在 A 实例发起登录、回调却落到 B 实例而失败。
 */
public interface MedicalSsoStateStore {

    void save(String key, String value, Duration ttl);

    /**
     * 取出并立即删除。一次性语义是防重放的基础：同一个 state 或票据只能用一次。
     *
     * @return 不存在或已过期时返回 {@code null}
     */
    String take(String key);
}
