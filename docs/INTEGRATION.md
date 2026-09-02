# Spring Boot 子系统接入指南

本文面向需要接入医共体统一身份认证的 Spring Boot 系统。统一认证采用 OpenID Connect Authorization Code Flow，浏览器系统由 Spring Security 建立本地 Session，接口系统校验 Bearer JWT。

**最快的接入方式不是读这份文档。** 让认证平台管理员在门户的「子系统管理」里登记你的系统，登记完成后页面会直接给出填好真实参数的依赖、配置和代码，复制到项目里即可。本文是那份生成结果的完整背景说明，用于排查问题和理解约定。

按 JDK 版本选择接入组件：

| 子系统 | 接入组件 | 说明 |
| --- | --- | --- |
| Spring Boot 3.x / JDK 17+ | `medical-sso-spring-boot-starter` | |
| Spring Boot 2.3 - 2.7 / JDK 8 | `medical-sso-spring-boot2-starter` | 按 Java 8 字节码发布，见第十节 |

两套组件的包名、类名和方法完全一致，业务代码可以直接互换，只有 Maven 依赖和 `HttpSecurity` 写法不同。

## 一、接入前准备

认证平台管理员需要为每个子系统提供：

| 配置 | 示例 | 说明 |
| --- | --- | --- |
| Issuer URI | `https://sso.intra.example/auth/realms/medical` | 统一认证地址，由平台统一提供 |
| Client ID | `his-web` | 子系统唯一编码，不允许复用 |
| Client Secret | 单独交付 | 只能保存在服务端 |
| Redirect URI | `https://his.intra.example/login/oauth2/code/medical-sso` | 必须精确登记 |
| Post Logout URI | `https://his.intra.example/` | 退出后的返回地址 |

接入方需要提供：系统名称、系统编码、访问地址、回调地址、退出返回地址、联系人，以及需要建立的系统角色。

## 二、角色和字段约定

统一 Token 中只放身份和粗粒度授权信息：

| Claim | 含义 | 是否必填 |
| --- | --- | --- |
| `sub` | Keycloak 用户 UUID | 是 |
| `person_id` | 医共体统一人员 ID | 是 |
| `employee_no` | 工号 | 是 |
| `name` | 姓名，中文全名（如 `张三`） | 是 |
| `org_code` | 主职机构编码 | 是 |
| `dept_code` | 主职科室编码 | 是 |
| `org_codes` | 全部任职机构编码，含主职 | 否 |
| `dept_codes` | 全部任职科室编码，含主职 | 否 |
| `realm_access.roles` | 医共体通用角色 | 否 |
| `resource_access.<client-id>.roles` | 当前系统角色 | 否 |

**跨机构、多科室任职**：一个人可以在主职之外兼任其他科室，甚至跨医院。`org_code` / `dept_code` 始终是主职，语义和取值方式都没有变化，只按主职判断归属的系统不需要做任何改动。需要完整任职信息的系统再读 `org_codes` / `dept_codes`，它们含主职且主职排在第一位。

只有一处任职时 Keycloak 会把多值字段下发成字符串而不是数组，接入组件已经兼容两种形态；自行解析 Token 的系统要注意这一点。

角色分两层：

- Realm Role：跨系统通用身份，例如 `doctor`、`nurse`。
- Client Role：只属于一个系统，例如 `his-web/access`、`his-web/outpatient-doctor`。

每个系统至少建立一个 `access` Client Role。没有业务 RBAC 的旧系统只检查 `ROLE_CLIENT_ACCESS`，有现有 RBAC 的系统在首次登录时用 `person_id` 关联本地用户，并继续使用本地菜单、岗位和数据权限。

## 三、MVC 页面系统接入

### 安装本项目 Starter

在本项目根目录执行：

```bash
mvn -Dmaven.repo.local=.m2/repository -DskipTests install
```

接入系统增加依赖：

```xml
<dependency>
    <groupId>com.medical.union</groupId>
    <artifactId>medical-sso-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

### 添加配置

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          medical-sso:
            provider: medical-sso
            client-id: his-web
            client-secret: ${SSO_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            scope: openid,profile
        provider:
          medical-sso:
            issuer-uri: ${SSO_ISSUER:https://sso.intra.example/auth/realms/medical}

medical:
  sso:
    client-id: his-web
```

不要把生产 Secret 写进 Git。通过环境变量、容器 Secret 或配置中心注入。

### 添加安全配置

统一认证的所有客户端都强制 PKCE（`S256`）。Spring Security 只会给公共客户端自动附带 PKCE 参数，**保密客户端（配了 client-secret）默认不发**，因此必须显式开启，否则授权请求会被 Keycloak 直接拒绝。

```java
@Configuration
class SecurityConfiguration {

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
                        .userInfoEndpoint(userInfo ->
                                userInfo.oidcUserService(userService)))
                .build();
    }
}
```

需要的 import：

```java
import com.medical.union.sso.MedicalSsoSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
```

`MedicalSsoSecurity.pkceAuthorizationRequestResolver` 由接入组件提供，内部就是 Spring Security 的 S256 PKCE，不需要自己算摘要。

自检：跳转统一登录页时，浏览器地址栏的 authorize 链接应同时带 `code_challenge` 和 `code_challenge_method=S256`。两者缺一说明 PKCE 没生效。

PKCE 不替代 client-secret。保密客户端两者都要：secret 保护换取 Token 的后端调用，PKCE 保护浏览器上传递的授权码。

角色转换规则：

- `doctor` 转成 `ROLE_REALM_DOCTOR`
- `his-user` 转成 `ROLE_CLIENT_HIS_USER`
- `access` 转成 `ROLE_CLIENT_ACCESS`

### 获取统一用户

```java
@Controller
class HomeController {
    private final MedicalUserMapper mapper;

    HomeController(MedicalUserMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/me")
    @ResponseBody
    MedicalUser me(@AuthenticationPrincipal OidcUser principal) {
        return mapper.fromClaims(principal.getClaims());
    }
}
```

完整可运行代码见 `medical-sso-demo`。

## 四、REST API 接入

接口服务不建立浏览器 Session，只校验调用方携带的 Access Token。

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SSO_ISSUER:https://sso.intra.example/auth/realms/medical}

medical:
  sso:
    client-id: his-api
```

```java
@Configuration
class ApiSecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            MedicalJwtAuthenticationConverter converter) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().hasAuthority("ROLE_CLIENT_ACCESS"))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
```

接入组件默认校验 Access Token 的 `aud` 是否包含 `medical.sso.client-id`，不包含直接返回 401。

这项校验不能省。Keycloak 客户端默认 `fullScopeAllowed=true`，用户在**所有**系统的角色都会写进任意一个客户端签发的 Token。也就是说 A 系统拿到的 Token 里同样带着该用户在 B 接口的 `resource_access.<B>.roles`；B 接口如果只看角色不看 audience，就会把 A 的 Token 当成合法调用放行。

登记子系统时平台会自动挂 audience 映射，`aud` 里会有本系统的 Client ID。手工建的老客户端可能没有这个映射，过渡期可以先关掉：

```yaml
medical:
  sso:
    client-id: his-api
    require-audience: false      # 仅限过渡，补上 audience 映射后请改回 true
```

进一步收紧可以把客户端的 `fullScopeAllowed` 关掉，让 Token 只携带本系统相关角色。注意关闭后需要为客户端显式添加 Realm 角色的 Scope 映射，否则 `realm_access.roles` 会变空，依赖 `doctor`、`nurse` 的判断会失效。改动前先在测试环境验证。

## 五、已有账号和 RBAC 的系统

推荐采用“统一认证、本地授权”模式：

1. 用户在 Keycloak 完成登录。
2. 子系统用 `person_id` 查找本地用户映射。
3. 找到映射后建立原有业务上下文，继续使用本地 RBAC。
4. 找不到映射时显示“账号尚未关联”，不要按姓名或手机号自动猜测。

建议增加最小映射字段：

```text
local_user_id
person_id       唯一
enabled
last_login_at
```

不要同步或覆盖旧系统的业务权限表。菜单权限、病区权限、数据范围、处方权限仍由业务系统负责。

## 六、没有 RBAC 的系统

最小方案只使用 `access`：

```java
.anyRequest().hasAuthority("ROLE_CLIENT_ACCESS")
```

管理员给用户分配 `<client-id>/access` 后可以进入系统，撤销后新 Token 不再包含该角色。已经建立的本地 Session 不会立即消失，因此重要系统应缩短 Session，或增加集中退出与禁用用户后的会话清理流程。

## 七、统一退出

MVC 系统建议配置 OIDC RP-Initiated Logout：

```java
@Bean
LogoutSuccessHandler logoutSuccessHandler(
        ClientRegistrationRepository registrations) {
    OidcClientInitiatedLogoutSuccessHandler handler =
            new OidcClientInitiatedLogoutSuccessHandler(registrations);
    handler.setPostLogoutRedirectUri("{baseUrl}/");
    return handler;
}
```

然后挂到安全配置：

```java
.logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler))
```

退出请求应使用 POST，并保留 Spring Security 的 CSRF 防护。

## 八、验收清单

- 未登录访问受保护页面会跳转统一登录页。
- 跳转统一登录页的 authorize 链接带 `code_challenge` 与 `code_challenge_method=S256`。
- 登录后能取得正确的 `person_id`、工号、机构、科室。
- 用户只有本系统 `access` 角色时才允许进入。
- A 系统角色不会错误授予 B 系统权限。
- 退出后重新访问会要求再次登录。
- 回调地址、Issuer 均使用固定域名，不混用 IP、主机名和 HTTP/HTTPS。
- Secret 未出现在前端代码、日志和版本库。
- 子系统按 `person_id` 建立本地账号映射。

## 九、常见问题

### 跳转登录页时 Keycloak 报错拒绝请求

统一认证对所有客户端强制 PKCE。错误信息通常是 `Missing parameter: code_challenge_method` 或 `invalid_request`。原因是保密客户端没有显式开启 PKCE——Spring Security 只对公共客户端自动附带。按上面「添加安全配置」配好 `OAuth2AuthorizationRequestCustomizers.withPkce()` 即可。

排查顺序：先看 authorize 链接有没有 `code_challenge`；没有是客户端没开，有还报错就核对 Keycloak 客户端的 `pkce.code.challenge.method` 是否为 `S256`。

### 页面反复跳转登录

检查浏览器访问地址、Redirect URI、服务端识别的协议和域名是否一致。反向代理后必须正确传递 `X-Forwarded-Proto`、`X-Forwarded-Host`。

### 登录成功但返回 403

确认用户已经分配当前 Client 下的 `access` 角色，并确认 `medical.sso.client-id` 与 Keycloak Client ID 完全一致。

### 能登录但人员字段为空

确认用户设置了 `person_id`、`employee_no`、`org_code`、`dept_code`，并确认 Client 挂载了 `medical-profile` 默认 Scope。

### 修改角色后没有立即生效

角色写入 Token，旧 Token 和本地 Session 可能仍然有效。退出后重新登录验证；生产环境根据风险设置较短的 Access Token 和应用 Session。

## 十、JDK 8 子系统接入

子系统还在 Java 8 上时使用 `medical-sso-spring-boot2-starter`。它按 Java 8 字节码发布，编译基线是 Spring Boot 2.7.18，内部只使用 Spring Security 5.3 起就稳定的 API，可用于 Spring Boot 2.3 - 2.7。项目实测覆盖 Spring Boot 2.7.18，更低版本请自行回归一次登录流。

Spring Boot 2.0 - 2.2 不在支持范围内：这些版本的 Spring Security 没有 `setAuthorizationRequestCustomizer`，无法按统一认证的要求发送 PKCE 参数。这类系统需要先升到 2.3 以上，或改由前置网关代为认证。

### 依赖

```xml
<dependency>
    <groupId>com.medical.union</groupId>
    <artifactId>medical-sso-spring-boot2-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

### 配置

配置项与 JDK 17 版本完全相同，直接照第三节的 `application.yml` 填写。

### 安全配置

只有 `HttpSecurity` 的写法不同，Spring Boot 2.x 用 `authorizeRequests` 和 `antMatchers`：

```java
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
}
```

### 为什么 PKCE 这一行不能省

Spring Security 6（Boot 3）在没有显式配置时，会去容器里找 `OAuth2AuthorizationRequestResolver` Bean 并自动采用；Spring Security 5.7（Boot 2.x）不做这个查找，直接走内部字段，找不到就退回不带 PKCE 的默认实现。两边实测确认过这个差异。

因此接入组件没有把解析器做成自动装配的 Bean：那样只有 JDK 17 子系统能白拿，JDK 8 子系统会静默地拿不到，两套技术栈行为不一致，反而更难排查。现在两边都要求显式写这一行，行为一致且看得见。

不要因为在 JDK 17 项目上删掉这一行仍然能登录，就认为它可有可无——同样的删改在 JDK 8 项目上会直接导致 Keycloak 拒绝授权请求。

### 取统一用户

与 JDK 17 版本一致。`MedicalUser` 在这里是普通类而不是 record，但访问方法名相同，`user.personId()` 一类的调用可以直接复制。同时保留了 `getPersonId()` 形式的 getter，方便老框架和 JSON 序列化。

完整可运行代码见 `medical-sso-demo-boot2`。

## 十一、在门户中登记子系统

认证平台管理员在门户的「子系统管理」中登记，只需要四项：系统名称、系统编码、系统访问地址、子系统技术栈。

登记后自动完成：

- 建立 Keycloak 客户端并生成 Client Secret
- 按访问地址推导回调地址、退出返回地址和 Web Origin
- 强制 PKCE `S256`
- 挂载 `medical-profile` 默认 Scope 和 audience 映射
- 建立本系统的 `access` 角色

随后页面直接给出该系统专属的对接文档：依赖、`application.yml`、安全配置、取用户代码都已填好真实的 Client ID、Secret 和 Issuer，可以逐段复制。

Client Secret 只在登记完成后展示一次，请立即交给子系统负责人并存入密钥系统。页面关闭后需要重新生成。

登记完成后仍需人工做一件事：给用户分配该系统的 `access` 角色，否则用户能登录但会被子系统拒绝。
