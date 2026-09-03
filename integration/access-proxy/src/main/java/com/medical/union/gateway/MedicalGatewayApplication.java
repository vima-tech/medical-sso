package com.medical.union.gateway;

import com.medical.union.sso.MedicalSsoExchange;
import com.medical.union.sso.MedicalSsoInMemoryStateStore;
import com.medical.union.sso.MedicalSsoProperties;
import com.medical.union.sso.MedicalSsoStateStore;
import com.medical.union.sso.MedicalUserMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * 接入网关：站在业务系统前面替它完成统一身份登录。
 *
 * <p>用于改不动的系统——没有源码、语言不是 Java、或者动一行都要走变更流程。
 * 部署形态是每个业务系统前面挂一个网关进程，指向该系统的真实地址。
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class MedicalGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedicalGatewayApplication.class, args);
    }

    @Bean
    MedicalSsoStateStore gatewayStateStore() {
        return new MedicalSsoInMemoryStateStore();
    }

    @Bean
    JwtDecoder gatewayIdTokenDecoder(MedicalSsoProperties properties) {
        String issuer = properties.getBridge().getIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("请配置 medical.sso.bridge.issuer-uri 指向统一认证");
        }
        return JwtDecoders.fromIssuerLocation(issuer);
    }

    @Bean
    MedicalSsoExchange gatewayExchange(MedicalSsoProperties properties, MedicalUserMapper userMapper,
                                       JwtDecoder gatewayIdTokenDecoder, RestClient.Builder builder) {
        return new MedicalSsoExchange(properties, userMapper, gatewayIdTokenDecoder, builder);
    }

    @Bean
    GatewaySessionStore gatewaySessionStore() {
        return new GatewaySessionStore();
    }

    /**
     * 转发用的 HTTP 客户端。必须关掉自动跟随重定向：
     * 业务系统返回的 302 是给浏览器的，网关跟过去会把用户的地址栏和登录流程搞乱。
     */
    @Bean
    UpstreamProxy upstreamProxy(GatewayProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        RestClient http = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(client))
                .build();
        return new UpstreamProxy(properties, http);
    }

    @Bean
    GatewayAuthFilter gatewayAuthFilter(GatewayProperties gateway, MedicalSsoProperties sso,
                                        MedicalSsoExchange exchange, MedicalSsoStateStore stateStore,
                                        GatewaySessionStore sessions, UpstreamProxy proxy) {
        return new GatewayAuthFilter(gateway, sso, exchange, stateStore, sessions, proxy);
    }
}
