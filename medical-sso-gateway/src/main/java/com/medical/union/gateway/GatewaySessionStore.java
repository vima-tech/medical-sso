package com.medical.union.gateway;

import com.medical.union.sso.MedicalUser;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关上的登录状态。
 *
 * <p>只放在内存里：网关重启后用户重新登录一次即可，统一认证那边的会话还在，
 * 这一步是无感的。要做多实例部署时把这个类换成 Redis 实现。
 */
public class GatewaySessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    public String create(MedicalUser user, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String id = ENCODER.encodeToString(bytes);
        sessions.put(id, new Entry(user, Instant.now().plus(ttl)));
        return id;
    }

    public Optional<MedicalUser> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Entry entry = sessions.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(entry.user());
    }

    public void remove(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    public int size() {
        return sessions.size();
    }

    private record Entry(MedicalUser user, Instant expiresAt) {
    }
}
