package com.medical.union.portal.admin;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关闭子系统登记功能后门户必须仍能启动。
 *
 * <p>控制器由组件扫描注册、配置类却带开关时，关掉开关会让控制器找不到依赖，
 * 整个门户启动失败。这里把两种开关状态都钉死。
 */
class SubsystemAdminToggleTest {

    @Nested
    @SpringBootTest(properties = "portal.admin.enabled=false")
    @ActiveProfiles("test")
    class WhenDisabled {

        @Autowired
        ApplicationContext context;

        @Test
        void portalStartsAndExposesNoAdminBeans() {
            assertThat(context.getBeanNamesForType(AdminLandingController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SubsystemAdminController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(StaffAdminController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(OrganizationAdminController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SubsystemRegistry.class)).isEmpty();
            assertThat(context.getBeanNamesForType(StaffRegistry.class)).isEmpty();
            assertThat(context.getBeanNamesForType(OrganizationRegistry.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ApplicationDirectory.class)).isEmpty();
            assertThat(context.getBeanNamesForType(KeycloakAdminClient.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = "portal.admin.enabled=true")
    @ActiveProfiles("test")
    class WhenEnabled {

        @Autowired
        ApplicationContext context;

        @Test
        void portalStartsWithAdminBeans() {
            assertThat(context.getBeanNamesForType(AdminLandingController.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(SubsystemAdminController.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(StaffAdminController.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(OrganizationAdminController.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(SubsystemRegistry.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(StaffRegistry.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(OrganizationRegistry.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(ApplicationDirectory.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(KeycloakAdminClient.class)).hasSize(1);
        }
    }
}
