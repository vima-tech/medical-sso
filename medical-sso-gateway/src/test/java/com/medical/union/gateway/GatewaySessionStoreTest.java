package com.medical.union.gateway;

import com.medical.union.sso.MedicalUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySessionStoreTest {

    @Test
    void createsReadsAndRevokesSession() {
        GatewaySessionStore sessions = new GatewaySessionStore();
        MedicalUser user = new MedicalUser("subject", "person", "10086", "zhangsan", "张三",
                "H001", "D001", List.of("H001"), List.of("D001"),
                Set.of("doctor"), Set.of("access"));

        String id = sessions.create(user, Duration.ofMinutes(5));

        assertThat(id).hasSizeGreaterThan(30);
        assertThat(sessions.find(id)).contains(user);
        assertThat(sessions.size()).isEqualTo(1);

        sessions.remove(id);
        assertThat(sessions.find(id)).isEmpty();
        assertThat(sessions.size()).isZero();
    }

    @Test
    void rejectsMissingSession() {
        assertThat(new GatewaySessionStore().find(null)).isEmpty();
        assertThat(new GatewaySessionStore().find("unknown")).isEmpty();
    }
}
