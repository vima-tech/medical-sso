package com.medical.union.sso;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
}

