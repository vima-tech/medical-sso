package com.medical.union.portal.admin;

import java.util.List;

/**
 * 按子系统技术栈生成对接代码。Boot 2（JDK 8）与 Boot 3（JDK 17）只有依赖和
 * HttpSecurity 写法不同，业务代码完全一致。
 */
public class IntegrationGuideFactory {

    // 不要把管理员指向认证内核：它的管理控制台已在引擎层面关闭，进不去。
    // 重新生成入口就在本平台的系统详情页。
    private static final String SECRET_PLACEHOLDER =
            "登记时展示过一次。未保存的话，可在该系统的详情页点「重新生成 Secret」";

    private final PortalAdminProperties properties;

    public IntegrationGuideFactory(PortalAdminProperties properties) {
        this.properties = properties;
    }

    public IntegrationGuide create(Subsystem subsystem, String stack, String clientSecret) {
        if (SubsystemForm.Stack.GATEWAY.equals(stack)) {
            return gatewayGuide(subsystem, clientSecret);
        }
        if (SubsystemForm.Stack.BRIDGE.equals(stack)) {
            return bridgeGuide(subsystem, clientSecret);
        }
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

    /**
     * 已有账号体系的系统：协议部分全部收进组件，系统只回答一个问题——
     * 「统一认证告诉我这个人是谁了，我给他发什么凭证」。原有登录可以继续用。
     */
    private IntegrationGuide bridgeGuide(Subsystem subsystem, String clientSecret) {
        String issuer = properties.getIssuerUri();
        String clientId = subsystem.clientId();
        String baseUrl = subsystem.baseUrl();
        String redirectUri = subsystem.redirectUris().isEmpty()
                ? baseUrl + SubsystemRegistry.BRIDGE_REDIRECT_PATH
                : subsystem.redirectUris().get(0);
        boolean secretVisible = clientSecret != null && !clientSecret.isBlank();

        List<IntegrationGuide.Snippet> snippets = List.of(
                new IntegrationGuide.Snippet(
                        "1. Maven 依赖",
                        "xml",
                        "pom.xml",
                        "JDK 8 的系统把 artifactId 换成 medical-sso-spring-boot2-starter，其余完全一样。"
                                + "解析不到依赖时，先确认平台侧已把组件的父 POM 一并发布到仓库。",
                        bridgeDependencies()),
                new IntegrationGuide.Snippet(
                        "2. 配置",
                        "yaml",
                        "application.yml",
                        "client-secret 用环境变量注入，Secret 本身见页面顶部，不要写进 Git。"
                                + "local-login 决定原有的账号密码登录怎么处理。",
                        bridgeYaml(clientId, issuer, baseUrl)),
                new IntegrationGuide.Snippet(
                        "3. 唯一要写的类",
                        "java",
                        "SsoIdentityBridge.java",
                        "跳转、换码、验签、防重放、一次性票据全部由组件完成。"
                                + "这个类只回答一个问题：这个人是谁确定了，给他发什么凭证。",
                        bridgeImplementation()),
                new IntegrationGuide.Snippet(
                        "4. 组件提供的端点",
                        "text",
                        "",
                        "这些端点不用自己写，加上依赖和配置后自动生效。放行它们即可。",
                        bridgeEndpoints()),
                new IntegrationGuide.Snippet(
                        "5. 登录页怎么摆",
                        "text",
                        "",
                        "推荐「统一认证为主，本地登录折叠」：既不影响老用户，也把新用户导向统一入口。",
                        bridgeLoginPage()),
                new IntegrationGuide.Snippet(
                        "6. 前端要做的三件事",
                        "javascript",
                        "",
                        "后端配好还进不去，多半是漏了这一步。组件把浏览器送回前端之后，"
                                + "剩下的换凭证和绑定表单要前端接住。",
                        bridgeFrontend()),
                new IntegrationGuide.Snippet(
                        "7. 回调地址登记值",
                        "text",
                        "",
                        // 这里是 HTML 页面不是 Markdown，别写 ** 强调，会原样显示出来
                        "已在统一认证中登记为下列地址。前后端分离的系统请注意：这里填的是"
                                + "用户浏览器访问的地址（通常是前端），不是后端服务的地址。",
                        redirectUri));

        return new IntegrationGuide(
                clientId,
                subsystem.name(),
                SubsystemForm.Stack.BRIDGE,
                "已有登录体系 / 桥接模式",
                issuer,
                baseUrl,
                redirectUri,
                secretVisible ? clientSecret : SECRET_PLACEHOLDER,
                secretVisible,
                snippets);
    }

    private String bridgeDependencies() {
        return """
                <dependency>
                    <groupId>com.medical.union</groupId>
                    <artifactId>medical-sso-spring-boot-starter</artifactId>
                    <version>0.1.0</version>
                </dependency>
                <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
                </dependency>""";
    }

    private String bridgeYaml(String clientId, String issuer, String baseUrl) {
        return """
                server:
                  # 反向代理后面必须有这一行，否则组件拼出来的回调地址是内网地址，
                  # 与登记值对不上，登录后回不来。
                  forward-headers-strategy: framework

                medical:
                  sso:
                    client-id: %s
                    bridge:
                      enabled: true
                      issuer-uri: %s
                      client-secret: ${SSO_CLIENT_SECRET}
                      base-url: %s
                      redirect-uri: %s/api/auth/sso/callback
                      # 原有账号密码登录怎么处理：
                      #   enabled         并存，登录页收在「其他登录方式」里（推荐）
                      #   emergency-only  只留应急账号，普通人员必须走统一认证
                      #   disabled        完全关闭
                      local-login: enabled
                      # 身份没关联到本地账号时，是否允许用旧账号密码自助绑定
                      self-service-binding: true
                      # 下面三个是「组件把浏览器送回前端」的落点，必须与前端路由一一对应。
                      # 用的就是这三个默认值时可以省略不写，但前端那两个页面一定要有。
                      success-uri: /sso/callback
                      bind-uri: /sso/bind
                      failure-uri: /login""".formatted(clientId, issuer, baseUrl, baseUrl);
    }

    private String bridgeImplementation() {
        return """
                package com.example.sso;

                import com.medical.union.sso.MedicalIdentityBridge;
                import com.medical.union.sso.MedicalUser;
                import org.springframework.stereotype.Component;

                @Component
                public class SsoIdentityBridge implements MedicalIdentityBridge {

                    private final AccountStore accounts;   // 换成本系统自己的用户表/服务

                    public SsoIdentityBridge(AccountStore accounts) {
                        this.accounts = accounts;
                    }

                    /**
                     * 统一认证已确认身份。返回本系统的凭证（token 或 sessionId）；
                     * 返回 null 表示这个人还没关联本系统账号，组件会引导他去自助绑定。
                     */
                    @Override
                    public String onAuthenticated(MedicalUser identity) {
                        return accounts.findByPersonId(identity.personId())
                                .map(accounts::issueToken)   // 换成你自己发凭证的那一行
                                .orElse(null);
                    }

                    /**
                     * 首次绑定：确认「统一身份里的这个人」就是「本系统的这个旧账号」。
                     * 走到这里说明统一认证已经通过，这一步只用旧密码确认是同一个人。
                     */
                    @Override
                    public String bind(MedicalUser identity, String username, String password) {
                        // 账号不存在与密码不对必须给同一句提示，否则这个接口能被用来枚举账号
                        Account account = accounts.findByUsername(username)
                                .filter(a -> a.passwordMatches(password))
                                .orElseThrow(() -> new IllegalArgumentException("原账号或密码不正确"));
                        if (!account.isEnabled()) {
                            throw new IllegalArgumentException("该账号已停用，请联系信息科");
                        }
                        // 已经归属别的统一身份就不能再绑，否则等于顶替别人
                        if (account.personId() != null && !account.personId().equals(identity.personId())) {
                            throw new IllegalArgumentException("该账号已绑定其他统一身份，请联系信息科");
                        }
                        accounts.link(account, identity.personId());
                        return accounts.issueToken(account);
                    }
                }""";
    }

    private String bridgeEndpoints() {
        return """
                GET  /api/auth/sso/start            发起统一登录，可带 redirect 参数记住原页面
                GET  /api/auth/sso/callback         统一认证回调，组件内部处理
                POST /api/auth/sso/exchange-ticket  前端用一次性票据换回本系统凭证
                POST /api/auth/sso/bind             首次绑定

                凭证不直接放进跳转地址，而是用一次性票据换取，
                令牌因此不会进入浏览器历史与访问日志。票据一次有效，60 秒过期，重放被拒绝。

                安全配置里放行这四个端点即可，其余保持原样。""";
    }

    /**
     * 前端必须补的三件事。
     *
     * <p>这一节是实际接入时最容易漏的：后端接完、登录也跳出去了，回来却 404 或者
     * 被路由守卫踢回登录页，看起来像「组件不工作」，其实是前端还没接住。
     */
    private String bridgeFrontend() {
        return """
                ① 新增两条【公开】路由，与配置里的 success-uri / bind-uri 对应

                   { path: '/sso/callback', component: SsoCallback, meta: { public: true } }
                   { path: '/sso/bind',     component: SsoBind,     meta: { public: true } }

                   必须是公开路由：走到这两个地址时本系统还没有登录态，
                   被路由守卫拦下就会踢回登录页，登录流程直接死循环。

                ② 这两个页面各做一件事

                   /sso/callback ：取地址里的 ticket，POST 换回本系统凭证，然后进首页
                   /sso/bind     ：显示「原账号 + 原密码」两个输入框，POST 完成绑定

                ③ 调这两个端点不要用本系统的业务请求封装

                   组件返回的是裸 JSON（{ credential } 或 { message }），
                   不是本系统统一的响应信封。业务封装里的拦截器通常只认自己的
                   成功码，会把这里的正常响应判成错误。用原生 fetch：

                   const res = await fetch('/api/auth/sso/exchange-ticket', {
                     method: 'POST',
                     headers: { 'Content-Type': 'application/json' },
                     body: JSON.stringify({ ticket }),
                   })
                   const data = await res.json()
                   if (!res.ok) throw new Error(data.message)
                   // data.credential 就是本系统的凭证，之后的处理与原有登录完全一致

                   绑定同理，POST /api/auth/sso/bind
                   传 { bindTicket, username, password }。""";
    }

    private String bridgeLoginPage() {
        return """
                统一身份登录  ← 页面上唯一显眼的按钮，跳 /api/auth/sso/start?redirect=<原目标路径>

                ▸ 其他登录方式  ← 折叠区，展开后是原有的账号密码表单

                未关联的人第一次登录会被引导到绑定页，用旧账号密码确认一次，
                之后每次直接进。管理员不需要逐个处理未关联名单。""";
    }

    /**
     * 改不动的系统：在它前面挂一个接入网关，登录由网关完成，
     * 身份以普通请求头交给它。业务系统不引依赖、不加代码、不重新编译。
     */
    private IntegrationGuide gatewayGuide(Subsystem subsystem, String clientSecret) {
        String issuer = properties.getIssuerUri();
        String clientId = subsystem.clientId();
        String baseUrl = subsystem.baseUrl();
        String redirectUri = subsystem.redirectUris().isEmpty()
                ? baseUrl + SubsystemRegistry.GATEWAY_REDIRECT_PATH
                : subsystem.redirectUris().get(0);
        boolean secretVisible = clientSecret != null && !clientSecret.isBlank();

        List<IntegrationGuide.Snippet> snippets = List.of(
                new IntegrationGuide.Snippet(
                        "1. 调整业务系统的监听地址",
                        "text",
                        "",
                        "让业务系统只监听回环地址（如 127.0.0.1:9000），原来对外的端口留给网关。"
                                + "这一步是必须的：能绕开网关直连业务系统的人，可以自己伪造身份请求头冒充任何人。",
                        "原来：业务系统监听 0.0.0.0:8080，用户直接访问\n"
                                + "改为：业务系统监听 127.0.0.1:9000，网关监听 8080，用户访问网关"),
                new IntegrationGuide.Snippet(
                        "2. 网关配置",
                        "yaml",
                        "application.yml",
                        "把这段保存为网关的配置文件。client-secret 与网关口令都用环境变量注入，不要写进 Git。"
                                + "issuer 与 secret 放在 medical.sso.bridge 下是因为网关复用了同一套协议配置，"
                                + "不代表这个系统用的是桥接模式。",
                        gatewayYaml(clientId, issuer, baseUrl)),
                new IntegrationGuide.Snippet(
                        "3. 启动网关",
                        "text",
                        "",
                        "网关是一个独立进程，跟在业务系统旁边跑。业务系统本身不用动。",
                        "export SSO_CLIENT_SECRET=<页面顶部的 Secret>\n"
                                + "export GATEWAY_UPSTREAM_TOKEN=<自己定一个长随机串>\n"
                                + "java -jar medical-sso-access-proxy-0.1.0.jar \\\n"
                                + "     --spring.config.location=file:./application.yml"),
                new IntegrationGuide.Snippet(
                        "4. 业务系统怎么取当前用户",
                        "text",
                        "",
                        "登录后每个请求都会带上这几个头。取值方式就是读请求头，任何语言都一样。"
                                + "姓名是中文，按 URL 编码送出，读到后解码一次。",
                        gatewayHeaders()),
                new IntegrationGuide.Snippet(
                        "5. 拒绝绕开网关的直连",
                        "text",
                        "",
                        "网关每个请求都会带上约定的口令头。业务系统校验它，就能挡住直连伪造。"
                                + "口令只是第二道闸，第一道仍然是第 1 步的只监听回环。",
                        "PHP:  if (($_SERVER['HTTP_X_MEDICAL_GATEWAY_TOKEN'] ?? '') !== getenv('GATEWAY_TOKEN')) { http_response_code(403); exit; }\n"
                                + "Java: if (!token.equals(request.getHeader(\"X-Medical-Gateway-Token\"))) { response.sendError(403); return; }"),
                new IntegrationGuide.Snippet(
                        "6. 回调地址登记值",
                        "text",
                        "",
                        "已在统一认证中登记为下列地址，网关的对外访问地址必须与之一致。",
                        redirectUri));

        return new IntegrationGuide(
                clientId,
                subsystem.name(),
                SubsystemForm.Stack.GATEWAY,
                "改不动的系统 / 接入网关",
                issuer,
                baseUrl,
                redirectUri,
                secretVisible ? clientSecret : SECRET_PLACEHOLDER,
                secretVisible,
                snippets);
    }

    private String gatewayYaml(String clientId, String issuer, String baseUrl) {
        return """
                server:
                  port: 8080

                medical:
                  sso:
                    client-id: %s
                    bridge:
                      issuer-uri: %s
                      client-secret: ${SSO_CLIENT_SECRET}
                      redirect-uri: %s/__sso/callback
                  gateway:
                    # 业务系统的真实地址，应当只监听回环
                    upstream: http://127.0.0.1:9000
                    public-base-url: %s
                    # 与业务系统约定的口令，用来拒绝绕开网关的直连
                    upstream-token: ${GATEWAY_UPSTREAM_TOKEN}
                    # 不需要登录也能访问的路径前缀，通常是静态资源
                    public-paths:
                      - /static/
                      - /favicon.ico
                    session-ttl: 8h""".formatted(clientId, issuer, baseUrl, baseUrl);
    }

    private String gatewayHeaders() {
        return """
                X-Medical-Person-Id     统一人员标识，跨系统认人就靠它
                X-Medical-Employee-No   工号
                X-Medical-Username      登录名
                X-Medical-Name          姓名（URL 编码，需解码一次）
                X-Medical-Org-Code      所属机构编码
                X-Medical-Dept-Code     所属科室编码
                X-Medical-Roles         该系统内的角色，逗号分隔

                PHP:    $personId = $_SERVER['HTTP_X_MEDICAL_PERSON_ID'];
                Java:   String personId = request.getHeader("X-Medical-Person-Id");
                .NET:   var personId = Request.Headers["X-Medical-Person-Id"];
                Python: person_id = request.headers.get("X-Medical-Person-Id")""";
    }

    private String boot3Dependencies() {
        return """
                <dependency>
                    <groupId>com.medical.union</groupId>
                    <artifactId>medical-sso-spring-boot-starter</artifactId>
                    <version>0.1.0</version>
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
                    <version>0.1.0</version>
                </dependency>
                <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-oauth2-client</artifactId>
                </dependency>""";
    }

    private String applicationYaml(String clientId, String clientSecret, String issuer) {
        return """
                server:
                  # 应用几乎总是跑在反向代理后面。不配这一行，Spring 会用内网地址去拼
                  # redirect_uri，与登记的回调地址对不上，表现就是登录后一直跳回登录页。
                  # 代理发 X-Forwarded-* 是一半，应用采信它们是另一半，两边都要有。
                  forward-headers-strategy: framework

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
