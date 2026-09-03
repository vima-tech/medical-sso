package com.medical.union.portal.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PortalAdminProperties.class)
@ConditionalOnSubsystemAdmin
public class PortalAdminConfiguration {

    @Bean
    KeycloakAdminClient keycloakAdminClient(RestClient.Builder builder, PortalAdminProperties properties) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(10));
        return new KeycloakAdminClient(builder.clone().requestFactory(requests), properties);
    }

    @Bean
    SubsystemRegistry subsystemRegistry(KeycloakAdminClient admin) {
        return new SubsystemRegistry(admin);
    }

    @Bean
    IntegrationGuideFactory integrationGuideFactory(PortalAdminProperties properties) {
        return new IntegrationGuideFactory(properties);
    }

    @Bean
    OrganizationDirectory organizationDirectory(KeycloakAdminClient admin) {
        return new OrganizationDirectory(admin);
    }

    @Bean
    OrganizationRegistry organizationRegistry(KeycloakAdminClient admin) {
        return new OrganizationRegistry(admin);
    }

    @Bean
    ApplicationDirectory applicationDirectory(KeycloakAdminClient admin) {
        return new ApplicationDirectory(admin);
    }

    @Bean
    SubsystemDiagnostics subsystemDiagnostics(KeycloakAdminClient admin, RestClient.Builder builder) {
        return new SubsystemDiagnostics(admin, builder);
    }

    @Bean
    AuditCenter auditCenter(KeycloakAdminClient admin) {
        return new AuditCenter(admin);
    }

    @Bean
    StaffImporter staffImporter(StaffRegistry staff, OrganizationDirectory organizations) {
        return new StaffImporter(staff, organizations);
    }

    @Bean
    IdentityRegistry identityRegistry(KeycloakAdminClient admin) {
        return new IdentityRegistry(admin);
    }

    @Bean
    AuthorizationCenter authorizationCenter(KeycloakAdminClient admin, StaffRegistry staff,
                                            OrganizationDirectory organizations) {
        return new AuthorizationCenter(admin, staff, organizations);
    }

    @Bean
    StaffRegistry staffRegistry(KeycloakAdminClient admin, OrganizationDirectory organizations) {
        return new StaffRegistry(admin, organizations);
    }
}
