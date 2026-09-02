package com.medical.union.portal.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PortalAdminProperties.class)
@ConditionalOnSubsystemAdmin
public class PortalAdminConfiguration {

    @Bean
    KeycloakAdminClient keycloakAdminClient(RestClient.Builder builder, PortalAdminProperties properties) {
        return new KeycloakAdminClient(builder, properties);
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
