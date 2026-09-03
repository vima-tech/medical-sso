package com.medical.union.portal;

import com.medical.union.sso.MedicalOidcUserService;
import com.medical.union.sso.MedicalSsoSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import java.util.LinkedHashMap;
import java.util.Map;

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
                withApplicationInitiatedActions(
                        MedicalSsoSecurity.pkceAuthorizationRequestResolver(registrations));
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


    /**
     * 让「账号与安全」里的改密码入口能把 kc_action 带进授权请求。
     *
     * <p>Spring 的授权端点不会把请求上的任意查询参数转发给认证内核，必须显式加进
     * additionalParameters。这里只认 {@code UPDATE_PASSWORD} 一个值：kc_action 能触发
     * 的动作不止改密码（还有删除账号一类），照单全收等于把这些动作全开成了 GET 链接。
     */
    private static OAuth2AuthorizationRequestResolver withApplicationInitiatedActions(
            OAuth2AuthorizationRequestResolver delegate) {
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return withAction(delegate.resolve(request), request);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                return withAction(delegate.resolve(request, clientRegistrationId), request);
            }
        };
    }

    private static OAuth2AuthorizationRequest withAction(
            OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request) {
        if (authorizationRequest == null
                || !AccountController.UPDATE_PASSWORD_ACTION.equals(request.getParameter("kc_action"))) {
            return authorizationRequest;
        }
        Map<String, Object> parameters =
                new LinkedHashMap<>(authorizationRequest.getAdditionalParameters());
        parameters.put("kc_action", AccountController.UPDATE_PASSWORD_ACTION);
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(parameters)
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
