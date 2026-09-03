package com.medical.union.sso;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MedicalSsoProperties.class)
public class MedicalSsoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MedicalUserMapper medicalUserMapper(MedicalSsoProperties properties) {
        return new MedicalUserMapper(properties);
    }

    /** 只有引入了 oauth2-client 才注册，避免纯接口服务加载不存在的类。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OidcUserService.class)
    static class OidcClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MedicalOidcUserService medicalOidcUserService(MedicalSsoProperties properties) {
            return new MedicalOidcUserService(properties);
        }
    }

    /** 只有引入了 resource-server 才注册。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JwtAuthenticationToken.class)
    static class ResourceServerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MedicalJwtAuthenticationConverter medicalJwtAuthenticationConverter(MedicalSsoProperties properties) {
            return new MedicalJwtAuthenticationConverter(properties);
        }
    }

    /**
     * 桥接模式。只有子系统提供了 {@link MedicalIdentityBridge} 且打开开关时才装配，
     * 对不使用桥接模式的系统没有任何影响。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "medical.sso.bridge", name = "enabled", havingValue = "true")
    @ConditionalOnBean(MedicalIdentityBridge.class)
    static class BridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MedicalSsoStateStore medicalSsoStateStore() {
            // 单实例够用。多实例部署请提供自己的实现（通常是 Redis），
            // 否则用户可能在 A 实例发起登录、回调却落到 B 实例而失败。
            return new MedicalSsoInMemoryStateStore();
        }

        @Bean
        @ConditionalOnMissingBean(name = "medicalIdTokenDecoder")
        public JwtDecoder medicalIdTokenDecoder(MedicalSsoProperties properties) {
            String issuer = properties.getBridge().getIssuerUri();
            if (issuer == null || issuer.trim().isEmpty()) {
                throw new IllegalStateException("开启桥接模式必须配置 medical.sso.bridge.issuer-uri");
            }
            return JwtDecoders.fromIssuerLocation(issuer);
        }

        @Bean
        @ConditionalOnMissingBean(name = "medicalSsoRestTemplate")
        public RestTemplate medicalSsoRestTemplate() {
            return new RestTemplate();
        }

        @Bean
        @ConditionalOnMissingBean
        public MedicalSsoExchange medicalSsoExchange(MedicalSsoProperties properties, MedicalUserMapper userMapper,
                                                     JwtDecoder medicalIdTokenDecoder,
                                                     RestTemplate medicalSsoRestTemplate) {
            return new MedicalSsoExchange(properties, userMapper, medicalIdTokenDecoder, medicalSsoRestTemplate);
        }

        @Bean
        @ConditionalOnMissingBean
        public MedicalSsoBridgeController medicalSsoBridgeController(
                MedicalSsoProperties properties, MedicalSsoExchange exchange,
                MedicalSsoStateStore stateStore, MedicalIdentityBridge bridge) {
            return new MedicalSsoBridgeController(properties, exchange, stateStore, bridge);
        }
    }
}
