package com.medical.union.portal;

import com.medical.union.sso.MedicalOidcUserService;
import com.medical.union.sso.MedicalSsoSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

@Configuration
public class SecurityConfiguration {

    /** 单一身份源的授权入口。既当登录页，也当可恢复失败时自动重来的目标。 */
    private static final String AUTHORIZATION_URI = "/oauth2/authorization/medical-sso";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            MedicalOidcUserService userService,
            ClientRegistrationRepository registrations,
            LogoutSuccessHandler logoutSuccessHandler) throws Exception {
        OAuth2AuthorizationRequestResolver authorizationRequestResolver =
                MedicalSsoSecurity.pkceAuthorizationRequestResolver(registrations);
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/css/**", "/actuator/health", "/error", "/login-failed").permitAll()
                        // 平台级角色：能建 OAuth 客户端并取得 Client Secret，与机构级 organization-admin 分离
                        .requestMatchers("/admin/**").hasAuthority("ROLE_REALM_SSO_PLATFORM_ADMIN")
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        // 本平台只有一个身份源，没有「选哪个登录方式」这回事。
                        // 直接把授权端点当登录页，框架就不会再生成那张英文的 OAuth2 选择页。
                        .loginPage(AUTHORIZATION_URI)
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(authorizationRequestResolver))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(userService))
                        .failureHandler(new LoginRetryFailureHandler(AUTHORIZATION_URI)))
                .oauth2Client(Customizer.withDefaults())
                .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler))
                .build();
    }


    @Bean
    LogoutSuccessHandler logoutSuccessHandler(ClientRegistrationRepository registrations) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
}
