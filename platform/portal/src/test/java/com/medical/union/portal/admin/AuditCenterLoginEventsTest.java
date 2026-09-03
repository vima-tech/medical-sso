package com.medical.union.portal.admin;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 登录记录的两条展示规则。 */
class AuditCenterLoginEventsTest {

    private static final long TIME = 1_788_409_634_000L;

    private KeycloakAdminClient adminReturning(List<Map<String, Object>> clients,
                                               List<Map<String, Object>> events) {
        KeycloakAdminClient admin = mock(KeycloakAdminClient.class);
        when(admin.listClients()).thenReturn(clients);
        when(admin.loginEvents(anyList(), anyInt())).thenReturn(events);
        return admin;
    }

    private AuditCenter center(KeycloakAdminClient admin) {
        return new AuditCenter(admin, ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void 业务系统列显示登记的中文名() {
        KeycloakAdminClient admin = adminReturning(
                List.of(Map.of("clientId", "medical-demo", "name", "住院医生工作站")),
                List.of(Map.of("type", "LOGIN", "time", TIME, "clientId", "medical-demo")));

        assertThat(center(admin).loginEvents(null, 10))
                .singleElement()
                .extracting(AuditCenter.Entry::system)
                .isEqualTo("住院医生工作站");
    }

    /**
     * Keycloak 内置客户端的 name 是 ${client_account-console} 这样的消息键。
     * 照搬会让审计列表里出现一串 ${...}，只能退回显示 clientId。
     */
    @Test
    void 内置客户端的国际化占位符不当成名称() {
        KeycloakAdminClient admin = adminReturning(
                List.of(Map.of("clientId", "account-console", "name", "${client_account-console}")),
                List.of(Map.of("type", "LOGIN", "time", TIME, "clientId", "account-console")));

        assertThat(center(admin).loginEvents(null, 10))
                .singleElement()
                .extracting(AuditCenter.Entry::system)
                .isEqualTo("account-console");
    }

    /**
     * 一次登录必然伴随一次 CODE_TO_TOKEN，两条并排刷屏。默认视图只要业务事件，
     * 但不能把数据藏掉——「含令牌签发」要能取消过滤。
     */
    @Test
    void 默认不查令牌签发而排查选项不加过滤() {
        KeycloakAdminClient admin = adminReturning(List.of(), List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> types = ArgumentCaptor.forClass(List.class);

        center(admin).loginEvents(null, 10);
        center(admin).loginEvents(AuditCenter.ALL_TYPES, 10);
        center(admin).loginEvents("LOGIN_ERROR", 10);

        verify(admin, org.mockito.Mockito.times(3)).loginEvents(types.capture(), anyInt());
        List<List<String>> calls = types.getAllValues();
        assertThat(calls.get(0)).contains("LOGIN", "LOGOUT").doesNotContain("CODE_TO_TOKEN");
        assertThat(calls.get(1)).isEmpty();
        assertThat(calls.get(2)).containsExactly("LOGIN_ERROR");
    }
}
