package com.medical.union.portal.admin;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计时间戳必须按平台配置的时区渲染。
 *
 * <p>曾经用 {@code ZoneId.systemDefault()}：容器镜像没设 TZ，默认时区落到 UTC，
 * 管理员看到的时间比实际早 8 小时，排查「谁在什么时候动了什么」直接对错时间。
 * 本机开发时因为宿主机是 CST 反而看不出来，所以这里把时区显式钉死。
 */
class AuditCenterTimeZoneTest {

    /** 2026-09-03T04:27:14Z，落在东八区应显示为同日 12:27:14。 */
    private static final long EPOCH_MILLIS = 1_788_409_634_000L;

    private AuditCenter centerAt(String zone) {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        when(admin.loginEvents(any(), anyInt())).thenReturn(List.of(
                Map.of("type", "LOGIN", "time", EPOCH_MILLIS, "ipAddress", "10.0.0.1")));
        return new AuditCenter(admin, ZoneId.of(zone));
    }

    @Test
    void 东八区按本地时间显示() {
        assertThat(centerAt("Asia/Shanghai").loginEvents(null, 10))
                .singleElement()
                .extracting(AuditCenter.Entry::time)
                .isEqualTo("2026-09-03 12:27:14");
    }

    @Test
    void 换个时区同一时刻显示不同() {
        String shanghai = centerAt("Asia/Shanghai").loginEvents(null, 10).get(0).time();
        String utc = centerAt("UTC").loginEvents(null, 10).get(0).time();
        assertThat(shanghai).isNotEqualTo(utc);
    }
}
