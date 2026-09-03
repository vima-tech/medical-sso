package com.medical.union.sso;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(MedicalSsoProperties.class)
public class MedicalSsoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MedicalUserMapper medicalUserMapper(MedicalSsoProperties properties) {
        return new MedicalUserMapper(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    MedicalOidcUserService medicalOidcUserService(MedicalSsoProperties properties) {
        return new MedicalOidcUserService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    MedicalJwtAuthenticationConverter medicalJwtAuthenticationConverter(MedicalSsoProperties properties) {
        return new MedicalJwtAuthenticationConverter(properties);
    }

    /**
     * 桥接模式。只有子系统提供了 {@link MedicalIdentityBridge} 且打开开关时才装配，
     * 因此对不使用桥接模式的系统没有任何影响。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "medical.sso.bridge", name = "enabled", havingValue = "true")
    @ConditionalOnBean(MedicalIdentityBridge.class)
    static class BridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean
        MedicalSsoStateStore medicalSsoStateStore() {
            // 单实例够用。多实例部署请提供自己的实现（通常是 Redis），
            // 否则用户可能在 A 实例发起登录、回调却落到 B 实例而失败。
            return new MedicalSsoInMemoryStateStore();
        }

        /** 专用于校验身份令牌，不影响子系统自己的 JwtDecoder。 */
        @Bean
        @ConditionalOnMissingBean(name = "medicalIdTokenDecoder")
        JwtDecoder medicalIdTokenDecoder(MedicalSsoProperties properties) {
            String issuer = properties.getBridge().getIssuerUri();
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalStateException("开启桥接模式必须配置 medical.sso.bridge.issuer-uri");
            }
            return JwtDecoders.fromIssuerLocation(issuer);
        }

        @Bean
        @ConditionalOnMissingBean
        MedicalSsoExchange medicalSsoExchange(MedicalSsoProperties properties, MedicalUserMapper userMapper,
                                              JwtDecoder medicalIdTokenDecoder, RestClient.Builder builder) {
            return new MedicalSsoExchange(properties, userMapper, medicalIdTokenDecoder, builder);
        }

        @Bean
        @ConditionalOnMissingBean
        MedicalSsoBridgeController medicalSsoBridgeController(
                MedicalSsoProperties properties, MedicalSsoExchange exchange,
                MedicalSsoStateStore stateStore, MedicalIdentityBridge bridge) {
            return new MedicalSsoBridgeController(properties, exchange, stateStore, bridge);
        }
    }
}

