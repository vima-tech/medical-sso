package com.medical.union.sso;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的一次性凭据存储。单实例部署够用；多实例部署请自行提供 Redis 实现，
 * 否则登录发起与回调可能落在不同实例上。
 */
public class MedicalSsoInMemoryStateStore implements MedicalSsoStateStore {

    private static final class Entry {
        private final String value;
        private final Instant expiresAt;

        Entry(String value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<String, Entry>();

    @Override
    public void save(String key, String value, Duration ttl) {
        purgeExpired();
        entries.put(key, new Entry(value, Instant.now().plus(ttl)));
    }

    @Override
    public String take(String key) {
        Entry entry = entries.remove(key);
        if (entry == null || entry.expiresAt.isBefore(Instant.now())) {
            return null;
        }
        return entry.value;
    }

    /** 没有后台线程，靠每次写入时顺手清理，避免残留条目无限增长。 */
    private void purgeExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }
}
