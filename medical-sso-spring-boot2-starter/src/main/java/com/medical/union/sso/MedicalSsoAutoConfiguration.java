package com.medical.union.sso;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
}
