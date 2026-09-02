package com.medical.union.portal.admin;

import java.util.List;

/**
 * 按子系统技术栈生成对接代码。Boot 2（JDK 8）与 Boot 3（JDK 17）只有依赖和
 * HttpSecurity 写法不同，业务代码完全一致。
 */
public class IntegrationGuideFactory {

    private static final String SECRET_PLACEHOLDER = "登记时展示过一次，未保存可在 Keycloak 中重新生成";

    private final PortalAdminProperties properties;

    public IntegrationGuideFactory(PortalAdminProperties properties) {
        this.properties = properties;
    }

    public IntegrationGuide create(Subsystem subsystem, String stack, String clientSecret) {
        boolean boot2 = SubsystemForm.Stack.BOOT2.equals(stack);
        String issuer = properties.getIssuerUri();
        String clientId = subsystem.clientId();
        String baseUrl = subsystem.baseUrl();
        String redirectUri = subsystem.redirectUris().isEmpty()
                ? baseUrl + SubsystemRegistry.REDIRECT_PATH
                : subsystem.redirectUris().get(0);
        boolean secretVisible = clientSecret != null && !clientSecret.isBlank();
        // 配置片段永远只写占位符：这段 yaml 会被复制进子系统仓库，真实 Secret 不能进 Git
        String secretForSnippet = "${SSO_CLIENT_SECRET}";

        List<IntegrationGuide.Snippet> snippets = List.of(
                new IntegrationGuide.Snippet(
                        "1. Maven 依赖",
                        "xml",
                        "pom.xml",
                        boot2
                                ? "适用于 Spring Boot 2.3 - 2.7 / JDK 8。组件已按 Java 8 字节码发布。"
                                : "适用于 Spring Boot 3.x / JDK 17 及以上。",
                        boot2 ? boot2Dependencies() : boot3Dependencies()),
                new IntegrationGuide.Snippet(
                        "2. 配置",
                        "yaml",
                        "application.yml",
                        "client-secret 用环境变量注入，Secret 本身见页面顶部，不要写进 Git。",
                        applicationYaml(clientId, secretForSnippet, issuer)),
                new IntegrationGuide.Snippet(
                        "3. 安全配置",
                        "java",
                        "SecurityConfiguration.java",
                        "统一认证强制 PKCE，保密客户端必须显式开启，这一行不能省。",
                        boot2 ? boot2Security() : boot3Security()),
                new IntegrationGuide.Snippet(
                        "4. 取统一用户",
                        "java",
                        "HomeController.java",
                        "两套技术栈的调用方式完全相同。",
                        controller()),
                new IntegrationGuide.Snippet(
                        "5. 回调地址登记值",
                        "text",
                        "",
                        "已在统一认证中登记为下列地址，子系统的实际访问地址必须与之一致。",
                        redirectUri));

        return new IntegrationGuide(
                clientId,
                subsystem.name(),
                boot2 ? SubsystemForm.Stack.BOOT2 : SubsystemForm.Stack.BOOT3,
                boot2 ? "Spring Boot 2.x / JDK 8" : "Spring Boot 3.x / JDK 17+",
                issuer,
                baseUrl,
                redirectUri,
                secretVisible ? clientSecret : SECRET_PLACEHOLDER,
                secretVisible,
                snippets);
    }

    private String boot3Dependencies() {
        return """
                <dependency>
                    <groupId>com.medical.union</groupId>
                    <artifactId>medical-sso-spring-boot-starter</artifactId>
                    <version>0.1.0-SNAPSHOT</version>
                </dependency>
                <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-oauth2-client</artifactId>
                </dependency>""";
    }

    private String boot2Dependencies() {
        return """
                <dependency>
                    <groupId>com.medical.union</groupId>
                    <artifactId>medical-sso-spring-boot2-starter</artifactId>
                    <version>0.1.0-SNAPSHOT</version>
                </dependency>
                <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-oauth2-client</artifactId>
                </dependency>""";
    }

    private String applicationYaml(String clientId, String clientSecret, String issuer) {
        return """
                spring:
                  security:
                    oauth2:
                      client:
                        registration:
                          medical-sso:
                            provider: medical-sso
                            client-id: %s
                            client-secret: %s
                            authorization-grant-type: authorization_code
                            scope: openid,profile
                        provider:
                          medical-sso:
                            issuer-uri: %s

                medical:
                  sso:
                    client-id: %s""".formatted(clientId, clientSecret, issuer, clientId);
    }

    private String boot3Security() {
        return """
                package com.example.sso;

                import com.medical.union.sso.MedicalOidcUserService;
                import com.medical.union.sso.MedicalSsoSecurity;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
                import org.springframework.security.web.SecurityFilterChain;

                @Configuration
                public class SecurityConfiguration {

                    @Bean
                    SecurityFilterChain securityFilterChain(
                            HttpSecurity http,
                            MedicalOidcUserService userService,
                            ClientRegistrationRepository registrations) throws Exception {
                        return http
                                .authorizeHttpRequests(authorize -> authorize
                                        .requestMatchers("/css/**", "/error").permitAll()
                                        .anyRequest().hasAuthority("ROLE_CLIENT_ACCESS"))
                                .oauth2Login(login -> login
                                        .authorizationEndpoint(endpoint -> endpoint
                                                .authorizationRequestResolver(
                                                        MedicalSsoSecurity.pkceAuthorizationRequestResolver(registrations)))
                                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(userService)))
                                .build();
                    }
                }""";
    }

    private String boot2Security() {
        return """
                package com.example.sso;

                import com.medical.union.sso.MedicalOidcUserService;
                import com.medical.union.sso.MedicalSsoSecurity;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
                import org.springframework.security.web.SecurityFilterChain;

                @Configuration
                public class SecurityConfiguration {

                    @Bean
                    SecurityFilterChain securityFilterChain(
                            HttpSecurity http,
                            MedicalOidcUserService userService,
                            ClientRegistrationRepository registrations) throws Exception {
                        http
                                .authorizeRequests(authorize -> authorize
                                        .antMatchers("/css/**", "/error").permitAll()
                                        .anyRequest().hasAuthority("ROLE_CLIENT_ACCESS"))
                                .oauth2Login(login -> login
                                        .authorizationEndpoint(endpoint -> endpoint
                                                .authorizationRequestResolver(
                                                        MedicalSsoSecurity.pkceAuthorizationRequestResolver(registrations)))
                                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(userService)));
                        return http.build();
                    }
                }""";
    }

    private String controller() {
        return """
                package com.example.sso;

                import com.medical.union.sso.MedicalUser;
                import com.medical.union.sso.MedicalUserMapper;
                import org.springframework.security.core.annotation.AuthenticationPrincipal;
                import org.springframework.security.oauth2.core.oidc.user.OidcUser;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class HomeController {

                    private final MedicalUserMapper mapper;

                    public HomeController(MedicalUserMapper mapper) {
                        this.mapper = mapper;
                    }

                    @GetMapping("/api/me")
                    public MedicalUser me(@AuthenticationPrincipal OidcUser principal) {
                        // personId 是跨系统稳定人员标识，本地用户表按它建立映射
                        return mapper.fromClaims(principal.getClaims());
                    }
                }""";
    }
}
