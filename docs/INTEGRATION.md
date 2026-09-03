# Spring Boot 子系统接入指南

本文面向需要接入医共体统一身份认证的 Spring Boot 系统。统一认证采用 OpenID Connect Authorization Code Flow，浏览器系统由 Spring Security 建立本地 Session，接口系统校验 Bearer JWT。

**最快的接入方式不是读这份文档。** 让认证平台管理员在管理平台的「业务系统」里登记你的系统，登记完成后页面会直接给出填好真实参数的依赖、配置和代码，复制到项目里即可。本文是那份生成结果的完整背景说明，用于排查问题和理解约定。

先按你的系统情况选一种接入方式，四选一，不要挨个看完：

| 你的系统 | 接入方式 | 改动量 | 见 |
| --- | --- | --- | --- |
| Spring Boot，没有自己的登录体系 | 标准接入 | 两段配置 + 一个安全配置类 | 第三节；JDK 8 见第十节 |
| Spring Boot，已有账号密码登录，且要**两种登录并存** | 桥接模式 | 实现一个接口，共两个方法 | 第十二节 |
| 不是 Java、没有源码、或代码动不了 | 接入网关 | **零改动**，在它前面挂一个网关进程 | 第十三节 |
| 只提供接口、不做页面 | 资源服务 | 一段配置 | 第四节 |

按 JDK 版本选择接入组件（前两种方式都用它）：

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
    <version>0.1.0</version>
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
server:
  # 应用几乎总是跑在反向代理后面。不配这一行，Spring 会用内网地址拼 redirect_uri，
  # 与登记的回调地址对不上，表现就是登录后一直跳回登录页。
  # 代理发 X-Forwarded-* 是一半，应用采信它们是另一半，两边都要有。
  forward-headers-strategy: framework

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

检查浏览器访问地址、Redirect URI、服务端识别的协议和域名是否一致。

这里要两边都做到：反向代理必须正确传递 `X-Forwarded-Proto`、`X-Forwarded-Host`，
**应用也必须配 `server.forward-headers-strategy: framework` 去采信它们**。
只做代理那一半，Spring 根本不看这些头，照样用内网地址拼 `redirect_uri`。

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
    <version>0.1.0</version>
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

认证平台管理员在管理平台的「业务系统」中登记，只需要四项：系统名称、系统编码、系统访问地址、接入方式。

登记后自动完成：

- 建立 Keycloak 客户端并生成 Client Secret
- 按访问地址推导回调地址、退出返回地址和 Web Origin
- 强制 PKCE `S256`
- 挂载 `medical-profile` 默认 Scope 和 audience 映射
- 建立本系统的 `access` 角色

随后页面直接给出该系统专属的对接文档：依赖、`application.yml`、安全配置、取用户代码都已填好真实的 Client ID、Secret 和 Issuer，可以逐段复制。

Client Secret 只在登记完成后展示一次，请立即交给子系统负责人并存入密钥系统。页面关闭后需要重新生成。

登记完成后仍需人工做一件事：在「授权中心」给人员勾选该系统，否则用户能登录但会被子系统拒绝。

### 接入自检

配好之后先别急着调登录。在业务系统列表点「自检」，平台会逐项检查：系统是否启用、是否强制 PKCE、
回调地址与访问地址是否一致、有没有 `access` 角色、有没有挂 `medical-profile`、有没有 audience 映射、
有没有人被授权、平台能不能连上这个系统。每项异常都会给出该怎么改。

其中「回调地址与访问地址不一致」是最高频的故障，表现为登录后一直跳回登录页，看日志看不出问题。


## 十二、已有登录体系的系统（桥接模式）

适用于这种情况：系统已经有自己的账号密码登录和会话机制，接入统一认证以后，
**两种登录方式要并存**——统一认证是主入口，原有的账号密码登录不能停。

### 为什么不用标准接入

标准接入会让 Spring Security 接管登录和会话。已有登录体系的系统这么改，
等于把认证和会话整套换掉，前端拿令牌的方式、接口鉴权、单元测试全要跟着动。
桥接模式反过来：协议部分（跳转、换码、验签、防重放）全部收进组件，
系统只回答一个问题——**「统一认证告诉我这个人是谁了，我给他发什么凭证」**。

### 子系统只写这一个类

```java
@Component
public class LegacyIdentityBridge implements MedicalIdentityBridge {

    private final AccountStore accounts;

    /** 统一认证已确认身份。返回本系统的凭证；返回 null 表示这个人还没关联本系统账号。 */
    @Override
    public String onAuthenticated(MedicalUser identity) {
        return accounts.byPersonId(identity.personId())
                .map(accounts::issueToken)   // 换成你自己发令牌/建 Session 的那一行
                .orElse(null);
    }

    /** 首次绑定：用旧账号密码确认「统一身份里的这个人」就是「本系统的这个旧账号」。 */
    @Override
    public String bind(MedicalUser identity, String username, String password) {
        // 账号不存在与密码不对给同一句提示，否则这个接口能被用来枚举账号
        Account account = accounts.byUsername(username)
                .filter(a -> a.passwordMatches(password))
                .orElseThrow(() -> new IllegalArgumentException("原账号或密码不正确"));
        if (!account.isEnabled()) {
            throw new IllegalArgumentException("该账号已停用，请联系信息科");
        }
        // 已归属别的统一身份就不能再绑，否则等于顶替别人
        if (account.personId() != null && !account.personId().equals(identity.personId())) {
            throw new IllegalArgumentException("该账号已绑定其他统一身份，请联系信息科");
        }
        accounts.link(account, identity.personId());
        return accounts.issueToken(account);
    }
}
```

### 配置

```yaml
server:
  # 反向代理后面必须有这一行，否则组件拼出来的回调地址是内网地址，登录后回不来
  forward-headers-strategy: framework

medical:
  sso:
    client-id: your-system
    bridge:
      enabled: true
      issuer-uri: http://sso.intra.example/auth/realms/medical
      client-secret: ${SSO_CLIENT_SECRET}
      base-url: https://your-system.intra.example
      redirect-uri: https://your-system.intra.example/api/auth/sso/callback
      # 原有账号密码登录怎么处理：
      #   enabled         并存，登录页收在「其他登录方式」里（推荐）
      #   emergency-only  只留应急账号，普通人员必须走统一认证
      #   disabled        完全关闭
      local-login: enabled
      self-service-binding: true
      # 组件把浏览器送回前端的三个落点，必须与前端路由一一对应。
      # 用默认值时可以省略，但前端那两个页面一定要有。
      success-uri: /sso/callback
      bind-uri: /sso/bind
      failure-uri: /login
```

### 组件提供的端点

| 端点 | 用途 |
| --- | --- |
| `GET /api/auth/sso/start` | 发起统一登录，可带 `redirect` 参数记住原页面 |
| `GET /api/auth/sso/callback` | 统一认证回调，组件内部处理 |
| `POST /api/auth/sso/exchange-ticket` | 前端用一次性票据换回本系统凭证 |
| `POST /api/auth/sso/bind` | 首次绑定 |

凭证不直接放进跳转地址，而是用一次性票据换取。令牌因此不会进入浏览器历史和访问日志。
票据一次有效，60 秒过期，重放会被拒绝。

### 首次绑定是怎么走的

统一认证里的人和本系统的旧账号，最初是两笔独立的数据，没有对应关系。
第一次用统一身份登录时 `onAuthenticated` 返回 `null`，组件把用户引导到绑定页，
让他用**旧的账号密码**确认一次。确认之后统一人员标识写进本系统账号，
以后每次登录都直接进，不再需要绑定。

管理员批量预置了对应关系时不会走到这一步。没有预置的，用户自己就能完成，不用找管理员。

### 前端要做的三件事

这一步最容易漏：后端接完、也确实跳出去了，回来却 404 或被路由守卫踢回登录页，
看着像「组件不工作」，其实是前端还没接住。

**① 新增两条公开路由**，与配置里的 `success-uri` / `bind-uri` 对应：

```js
{ path: '/sso/callback', component: SsoCallback, meta: { public: true } }
{ path: '/sso/bind',     component: SsoBind,     meta: { public: true } }
```

必须是**公开**路由——走到这两个地址时本系统还没有登录态，被守卫拦下就会踢回登录页，
登录流程直接死循环。

**② 这两个页面各做一件事**：`/sso/callback` 取地址里的 `ticket` 换回本系统凭证后进首页；
`/sso/bind` 显示「原账号 + 原密码」两个输入框完成绑定。

**③ 调这两个端点不要用本系统的业务请求封装。** 组件返回的是裸 JSON
（`{ credential }` 或 `{ message }`），不是本系统统一的响应信封；业务封装里的拦截器
通常只认自己的成功码，会把这里的正常响应判成错误。用原生 `fetch`：

```js
const res = await fetch('/api/auth/sso/exchange-ticket', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ ticket }),
})
const data = await res.json()
if (!res.ok) throw new Error(data.message)
// data.credential 就是本系统的凭证，之后的处理与原有登录完全一致
```

绑定同理，`POST /api/auth/sso/bind` 传 `{ bindTicket, username, password }`。

### 回调地址填哪个

前后端分离的系统请注意：登记的回调地址是**用户浏览器访问的地址**（通常是前端），
不是后端服务的地址。例如前端 `https://sustain.intra.example`、后端在内网 `:8091`，
应登记 `https://sustain.intra.example/api/auth/sso/callback`，由前端的反向代理
或开发服务器把 `/api` 转给后端。填成后端地址，登录后就回不来了。

### 登录页怎么摆

推荐「统一认证为主，本地登录折叠」：统一登录是页面上唯一显眼的按钮，
原有的账号密码表单收进「其他登录方式」的折叠区。既不影响老用户，也把新用户导向统一入口。

登记时接入方式选「已有账号密码登录」，平台会自动把回调地址登记成 `/api/auth/sso/callback`，不需要再手工调整。

完整可运行代码见 `medical-sso-demo-legacy`，包含登录页、绑定页和一套模拟的旧账号体系。

## 十三、改不动的系统（接入网关）

适用于：不是 Java 写的、买来的成品没有源码、或者动一行都要走变更流程的系统。

### 原理

在业务系统前面挂一个网关进程。用户访问的是网关，网关替业务系统完成统一认证登录，
然后把请求原样转给业务系统，并**在请求头里带上当前登录人是谁**。

业务系统一行代码都不用改，也不需要理解 OIDC——它只是发现每个请求都多了几个请求头。
取当前用户的地方从「读自己的 Session」改成「读一个请求头」，就接上了。

```
用户 ──▶ 接入网关 :8080 ──▶ 业务系统 127.0.0.1:9000
           │                    ▲
           │                    └── X-Medical-Person-Id: P000123
           ▼                        X-Medical-Name: %E5%BC%A0%E4%B8%89
       统一认证登录
```

### 注入的请求头

| 请求头 | 内容 |
| --- | --- |
| `X-Medical-Person-Id` | 统一人员标识，跨系统认人就靠它 |
| `X-Medical-Employee-No` | 工号 |
| `X-Medical-Username` | 登录名 |
| `X-Medical-Name` | 姓名，**URL 编码**，读到后解码一次 |
| `X-Medical-Org-Code` | 所属机构编码 |
| `X-Medical-Dept-Code` | 所属科室编码 |
| `X-Medical-Roles` | 该系统内的角色，逗号分隔 |
| `X-Medical-Gateway-Token` | 网关口令，见下方安全前提 |

姓名单独做 URL 编码，是因为 HTTP 请求头只能放 ASCII，中文直接放进去会乱码或被拒绝。

```php
$personId = $_SERVER['HTTP_X_MEDICAL_PERSON_ID'];
$name     = urldecode($_SERVER['HTTP_X_MEDICAL_NAME'] ?? '');
```

### 安全前提（必须做，否则等于没有认证）

身份走请求头，只有在「业务系统只能收到网关转来的请求」这个前提下才成立。
能直连业务系统的人，可以自己加一个 `X-Medical-Person-Id` 头冒充任何人，包括院长。

两道闸，都要上：

1. **业务系统只监听回环地址**（如 `127.0.0.1:9000`），或用防火墙只放行网关。这是主要防线。
2. **校验网关口令**：网关每个请求都会带 `X-Medical-Gateway-Token`，业务系统比对一下，
   对不上就返回 403。这是第二道闸。

网关这一侧已经做了对应的防护：浏览器送来的所有 `X-Medical-*` 请求头一律丢弃后才转发，
外部无法通过伪造同名请求头把身份塞进来。

### 配置与启动

```yaml
server:
  port: 8080          # 原来业务系统对外的端口，现在给网关

medical:
  sso:
    client-id: your-system
    bridge:
      issuer-uri: http://sso.intra.example/auth/realms/medical
      client-secret: ${SSO_CLIENT_SECRET}
      redirect-uri: https://your-system.intra.example/__sso/callback
  gateway:
    upstream: http://127.0.0.1:9000        # 业务系统的真实地址
    public-base-url: https://your-system.intra.example
    upstream-token: ${GATEWAY_UPSTREAM_TOKEN}
    public-paths:                          # 不需要登录的路径前缀
      - /static/
      - /favicon.ico
    session-ttl: 8h
```

```bash
java -jar medical-sso-gateway-0.1.0.jar --spring.config.location=file:./application.yml
```

登记时接入方式选「改不动的系统」，平台会自动把回调地址登记成 `/__sso/callback`。

### 几个行为约定

- 未登录访问页面会跳转统一登录页；登录完回到**原本要去的那个页面**，不是首页。
- 未登录的接口请求（`Accept: application/json`）返回 **401**，不跳转。
  否则前端的 fetch 会跟到登录页，拿到一段 HTTP 当 JSON 解析，报出与登录毫不相干的错。
- `/__sso/logout` 只退本系统，不动统一认证的登录状态——是否要连带退出其他系统，
  不该由某一个业务系统替用户决定。
- 登录状态放在网关进程内存里。网关重启后用户重新登录一次，因为统一认证那边的会话还在，这一步是无感的。
  要多实例部署时需要把它换成共享存储。
