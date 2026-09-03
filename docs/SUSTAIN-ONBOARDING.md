# Sustain 接入统一身份认证 实施方案

面向 Sustain v4（临床营养管理系统）接入医共体统一身份认证平台。本文基于对 Sustain 现有代码的实际调阅编写，改动点已定位到具体文件。

## 一、结论先行

**推荐方案：桥接模式——协议部分交给接入组件，Sustain 保留自己的会话体系。**

Sustain 只把「如何证明你是谁」换成统一认证，「登录之后怎么维持会话、怎么判权限」全部保持原样。

具体说：用户点登录 → 跳统一登录页 → 认证通过后组件把身份交给 Sustain → **Sustain 照旧调用 `tokenService.generateToken(username)` 发一个自己的 Redis token 给前端**。

这样做的直接收益是，下面这些东西一行都不用改：

| Sustain 现有能力 | 是否受影响 |
| --- | --- |
| `TokenAuthFilter`（含 SSE 的 query token 通道、滑动续期、`RequestAttributeSecurityContextRepository` 那段处理） | 不动 |
| `PermissionService` 的 perms / menuPaths 缓存 | 不动 |
| `@PreAuthorize("@perm.has('xxx')")` 按钮级鉴权 | 不动 |
| 在线用户、踢下线（`removeTokenByUsername`） | 不动 |
| 登录日志、操作日志 | 不动 |
| 前端 `request.ts` 的 `Authorization: Bearer`、`user store` 的 token 存取 | 不动 |
| 菜单、角色、权限点、全部业务功能 | 不动 |
| **Sustain 原有的账号密码登录** | **不动，与统一认证并存** |

**Sustain 侧只需要写一个类，两个方法。** 跳转、`state` 防重放、PKCE、用授权码换令牌、校验 ID Token 签名、一次性票据——这些全部由接入组件完成，不需要自己实现，也不需要理解。

## 二、为什么不选另外两种

**方案 B：改造成 OAuth2 Resource Server（前端直接持 Keycloak Token）**

前端跑 PKCE 拿 access_token，后端改成校验 Keycloak 签发的 JWT。理论上更「标准」，但对 Sustain 代价很大：

- `TokenAuthFilter` 要重写。它现在承载着 SSE 的 query token 通道和滑动续期，这两块都有踩过坑留下的注释，重写等于把这些经验清零。
- 踢下线失效。JWT 是自包含的，签发后无法主动作废；Sustain 的「在线用户 → 强制下线」会变成假功能。
- Access Token 过期时间由平台统一控制（当前 5 分钟），前端要处理静默续期，改动扩散到 `request.ts` 的拦截器。

**方案 C：保留原登录页，后端把账号密码转发给 Keycloak（Password 授权）**

看起来改动最小，实际是最差的：Sustain 会重新看到明文密码，SSO 最核心的安全价值直接丢掉；PKCE 用不上；将来上双因素、扫码登录、统一密码策略全都接不进来。该模式在 OAuth 2.1 中已被废弃，本平台默认未开启。

**结论**：桥接模式用最小改动换到了统一认证，且原有登录不受影响。

## 三、改动清单

### 3.1 平台侧（约 5 分钟，由平台管理员操作）

用具有 `sso-platform-admin` 身份的账号登录 `18081`，进「业务系统」→「登记业务系统」：

| 字段 | 填写值 |
| --- | --- |
| 系统名称 | 临床营养管理系统 |
| 系统编码 | `sustain-admin` |
| 系统访问地址 | Sustain 的正式访问地址，例如 `https://sustain.intra.example` |
| 接入方式 | **已有账号密码登录**（桥接模式） |

登记后拿到三个值：`client-id`、`client-secret`（只显示一次）、`issuer`。

平台会自动完成：建立客户端、强制 PKCE `S256`、**把回调地址登记成 `<访问地址>/api/auth/sso/callback`**、挂载身份字段、创建 `access` 角色。

接入方式选对了，回调地址就是对的，不需要再手工调整。

登记完成后页面直接给出 Sustain 专属的对接文档：依赖、配置、要写的那个类，都已填好真实的 `client-id` 和 `issuer`，可以逐段复制。

### 3.2 Sustain 后端

**依赖**（`backend/pom.xml`）

```xml
<dependency>
    <groupId>com.medical.union</groupId>
    <artifactId>medical-sso-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

Sustain 是 Spring Boot 3.3 / Java 21，用 `medical-sso-spring-boot-starter`。第二个依赖只是为了拿到 `JwtDecoder` 用于校验 ID Token 签名，不启用 Resource Server 过滤链。

**配置**（`application.yml`）

```yaml
medical:
  sso:
    client-id: sustain-admin
    bridge:
      enabled: ${SSO_ENABLED:true}            # 应急开关，见第七节
      issuer-uri: ${SSO_ISSUER:https://sso.intra.example/auth/realms/medical}
      client-secret: ${SUSTAIN_SSO_SECRET}    # 不要写进代码库
      base-url: ${SUSTAIN_BASE_URL}
      redirect-uri: ${SUSTAIN_BASE_URL}/api/auth/sso/callback
      success-uri: /sso/callback              # 前端路由
      bind-uri: /sso/bind                     # 前端路由
      failure-uri: /login
      local-login: enabled                    # 原有登录并存，见第六节
      self-service-binding: true              # 未关联人员可自助绑定，见第四节
```

**新增文件（只有一个）**

| 文件 | 职责 |
| --- | --- |
| `security/SustainIdentityBridge.java` | 实现 `MedicalIdentityBridge`：按 `person_id` 找本地用户发 token；找不到时用旧账号密码自助绑定 |

**修改文件**

| 文件 | 改动 |
| --- | --- |
| `entity/User.java` | 新增 `private String personId;` |
| `service/AuthService.java` | 新增 `loginBySso(MedicalUser)`，复用原有的停用校验、登录日志、`tokenService.generateToken` |
| `config/SecurityConfig.java` | `permitAll` 放行 `/api/auth/sso/**` |
| 数据库迁移 | `sys_user` 加 `person_id varchar(64)` 并建唯一索引 |

对比接入组件升级前：原方案还需要自己写 `SsoClient`（生成 state 与 code_verifier 存 Redis、拼授权 URL、换 Token、校验 ID Token）和 `SsoAuthController`（两个端点），并自建一次性票据机制。**这三样现在全部由组件提供，不用写了**，也不用自己维护那部分安全逻辑。

**唯一要写的类**

```java
@Component
public class SustainIdentityBridge implements MedicalIdentityBridge {

    private final UserRepository users;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    public SustainIdentityBridge(UserRepository users, AuthService authService,
                                 PasswordEncoder passwordEncoder) {
        this.users = users;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 统一认证已确认身份。返回 Sustain 自己的 token；
     * 返回 null 表示这个人还没关联本地账号，组件会引导他去自助绑定。
     */
    @Override
    public String onAuthenticated(MedicalUser identity) {
        User user = users.findByPersonId(identity.personId()).orElse(null);
        if (user == null) {
            return null;                       // 转入自助绑定，不是报错
        }
        return authService.loginBySso(user, identity);   // 复用原有登录后半段
    }

    /**
     * 首次绑定：确认「统一身份里的这个人」就是「Sustain 里的这个旧账号」。
     * 走到这里说明统一认证已经通过，这一步只用旧密码确认是同一个人。
     */
    @Override
    public String bind(MedicalUser identity, String username, String password) {
        User user = users.findByUsername(username)
                .filter(u -> passwordEncoder.matches(password, u.getPassword()))
                .orElseThrow(() -> new IllegalArgumentException("原账号或密码不正确"));
        if (user.getStatus() != 1) {
            throw new IllegalArgumentException("账号已停用");
        }
        user.setPersonId(identity.personId());
        users.save(user);                      // 关联关系写回，以后直接进
        return authService.loginBySso(user, identity);
    }
}
```

```java
// AuthService：新增方法，与原 login 共用后半段
public String loginBySso(User user, MedicalUser identity) {
    LoginLog log = new LoginLog();
    log.setUsername(user.getUsername());
    // ……与原 login 相同的日志字段

    if (user.getStatus() != 1) {
        log.setStatus(0);
        log.setMsg("用户已被禁用");
        logService.saveLoginLog(log);
        throw new IllegalArgumentException("账号已停用");
    }

    log.setStatus(1);
    log.setMsg("统一身份登录成功");
    logService.saveLoginLog(log);

    // 到这里完全回到原有路径：发本地 token，前端无感知
    return tokenService.generateToken(user.getUsername());
}
```

**组件自动提供的端点**（不用自己写，放行即可）

| 端点 | 用途 |
| --- | --- |
| `GET /api/auth/sso/start` | 发起统一登录，可带 `redirect` 参数记住原页面 |
| `GET /api/auth/sso/callback` | 统一认证回调，组件内部处理 |
| `POST /api/auth/sso/exchange-ticket` | 前端用一次性票据换回 Sustain 的 token |
| `POST /api/auth/sso/bind` | 首次绑定 |

Sustain 的 token 不直接放进跳转地址，而是用一次性票据换取，因此不会进入浏览器历史与访问日志。票据一次有效，60 秒过期，重放会被拒绝——这套机制由组件实现，不需要 Sustain 自建。

### 3.3 Sustain 前端（`apps/admin`）

改动很小，只有四处：

| 文件 | 改动 |
| --- | --- |
| `views/login/index.vue` | 「统一身份登录」按钮跳 `/api/auth/sso/start?redirect=<原目标路径>`；原有账号密码表单收进「其他登录方式」折叠区 |
| `views/login/bind.vue`（新增） | 首次绑定页：输旧账号密码，POST `/api/auth/sso/bind` |
| `router/index.ts` | 新增两个公开路由 `/sso/callback`、`/sso/bind` |
| `store/user.ts` | 新增 `loginByTicket(ticket)`，内部仍是 `token.value = ...` + `fetchUserInfo()`，与原 `login()` 后半段一致 |

`request.ts`、`v-auth` 指令、侧边栏、菜单过滤**全部不动**。

## 四、数据准备：person_id 对齐

**这一节的成本比过去低得多，因为有自助绑定。**

**做法**：由平台侧导出人员清单（姓名、工号、`person_id`、机构、科室），Sustain 侧按**工号**与本地 `sys_user` 比对，人工确认后回填 `person_id`。

**关键变化**：回填脚本不再需要「尽量匹配全」。匹配上的人下次登录直接进；**匹配不上的人不会被挡在门外**，而是被引导到绑定页，用他自己的 Sustain 旧账号密码确认一次，`person_id` 自动写回，之后每次直接进。信息科不需要逐个处理未关联名单。

**三条纪律仍然成立**：

1. **绝不按姓名自动匹配。** 医院重名极其常见，匹配错等于把 A 的病历权限给了 B。
2. **工号也要人工复核。** 工号在不同系统里可能有前导零、字母前缀的差异。
3. **没匹配上的账号不要猜。** 留空即可，交给用户自己绑定——他知道自己的旧密码，这比任何自动匹配都准确。

自助绑定为什么是安全的：走到绑定页时统一认证**已经通过**，身份是可信的；旧密码只用来确认「这个统一身份对应的是本系统的哪个旧账号」，不是用来认证的。两个凭证都对上，才建立关联。

**对不上的三类账号仍需提前决策**：

- 外包、实习、临时账号 → 是否纳入统一身份目录，还是长期走本地登录
- 系统内置账号（如 `admin`）→ 建议保留本地登录，作为统一认证故障时的应急入口（配 `local-login: emergency-only` 时仍可用）
- 已离职但未删除的账号 → 借这次机会清理。注意：**这些账号也能自助绑定**，所以清理要在开放绑定之前做

## 五、机构科室的对应关系

Sustain 的 `Dept` 表只有 `id / name`，**没有编码字段**；统一身份下发的是 `org_code` / `dept_code`（如 `H001` / `D001`）。

两种处理方式，建议选第一种：

1. **`Dept` 加一列 `dept_code`，与统一身份的科室编码对齐**。之后 Sustain 可以按 Token 里的科室做数据范围过滤，也为将来多院区做好准备。
2. **暂不对齐**，登录时忽略科室字段，沿用 Sustain 自己的 `deptId`。改动为零，但两边科室口径会长期分叉。

统一身份还会下发 `org_codes` / `dept_codes`（含兼职的全部任职）。Sustain 目前是单科室模型，**第一期可以只取主职 `dept_code`，忽略多值字段**；等有多点执业需求时再启用。

## 六、分三阶段实施

**过去这三个阶段要改代码，现在是一个配置项的三档切换。**

**第一阶段：并存（建议 2 到 4 周）** — `local-login: enabled`

登录页以「统一身份登录」为主入口，原有账号密码表单收在「其他登录方式」折叠区。已回填 `person_id` 的人直接进，未回填的走自助绑定，两条路都通。

这一阶段的目的是暴露数据对齐问题。观察指标：统一身份登录成功率、自助绑定次数与人员名单、绑定失败次数。

**第二阶段：只留应急账号** — `local-login: emergency-only`

普通人员必须走统一认证，只有标记为应急的账号还能用本地密码登录。清零剩余未绑定账号。

**第三阶段：关闭本地密码登录** — `local-login: disabled`

同时清理 `sys_user.password` 列的历史数据。

**每一阶段都可独立回滚**，改一个配置值重启即可；配置 `SSO_ENABLED=false` 则整体退回纯本地登录（组件的桥接端点不再装配，登录页回到原样）。

## 七、退出与踢下线

这里有一个必须提前讲清楚的语义差异：

**Sustain 的退出只结束 Sustain 的会话，不会退出统一认证。** 用户点退出后再点登录，会因为统一认证那边的登录态还在而直接进来，看起来像「没退出成功」。

三种处理，按场景选：

| 做法 | 效果 | 适用 |
| --- | --- | --- |
| 只清本地 token（默认） | 退出 Sustain，其他系统不受影响 | 日常退出，推荐 |
| 同时跳统一退出 | 一次退出全部系统 | 公用电脑、诊室共用终端 |
| 平台侧「强制下线」 | 结束统一认证会话 | 离职、账号外泄 |

**注意第三种的边界**：平台的强制下线只结束统一认证侧的会话，**Sustain 自己 Redis 里的 token 不会自动失效**，用户在 token 有效期内仍能继续操作。

离职处理的完整动作是：平台停用人员（自动注销统一认证会话）**加上** Sustain 侧 `removeTokenByUsername`。第一期可以由信息科手工执行两步，后续再考虑接平台的退出通知。

## 八、明确不动的部分

写清楚边界，避免实施时范围扩散：

- Sustain 的菜单、角色、权限点、`menuPaths`、数据范围，全部由 Sustain 自己管，统一认证不介入
- 患者端小程序（`apps/mp`、`MpAuthFilter`）**不接入**。患者用微信登录，不是医院员工，本就不该进员工身份目录。代码里 D-18 决策定下的两套 Redis 命名空间隔离要保持原样
- Sustain 的业务数据、接口、前端页面不受影响
- Sustain 原有的账号密码登录逻辑（`AuthService.login`）不动，由配置决定它是否对外可用

## 九、验收清单

**平台侧**

- [ ] 在业务系统列表点「自检」，各项全部通过（尤其「回调地址与访问地址一致」和「已有授权人员」）
- [ ] 已在授权中心给 Sustain 使用人员勾选该系统

**登录**

- [ ] 已关联人员点「统一身份登录」，跳转统一登录页，输工号密码后回到 Sustain 并正常进入
- [ ] **未关联人员被引导到绑定页**，用旧账号密码绑定成功后进入系统，`sys_user.person_id` 已写入
- [ ] 绑定时输错旧密码被拒绝，且不泄露该账号是否存在
- [ ] 绑定过一次之后再登录直接进入，不再出现绑定页
- [ ] 原有账号密码登录仍然可用（第一阶段）
- [ ] 已停用人员既不能统一认证登录，也不能绑定

**回归（容易漏的）**

- [ ] 登录后侧边栏菜单、按钮权限与接入前完全一致
- [ ] 在线用户列表能看到统一身份登录进来的人，强制下线可用
- [ ] SSE 消息推送正常（`TokenAuthFilter` 的 query token 通道要回归验证）
- [ ] 已在其他业务系统登录过的人访问 Sustain，不需要再输一次密码
- [ ] 登录票据一次性，重放被拒绝
- [ ] `SSO_ENABLED=false` 可退回纯本地登录
- [ ] 患者小程序端登录不受任何影响
- [ ] `client-secret` 未出现在代码库、前端代码和日志中

## 十、风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 离职账号未清理即开放自助绑定 | 离职人员可能把自己的统一身份绑到旧账号上 | **开放绑定前先清理离职账号**；`bind` 里已校验 `status != 1` 拒绝停用账号 |
| 认证平台故障 | 全院无法登录 | 保留应急本地账号（`local-login: emergency-only`）；平台侧按部署文档做双节点 |
| 回调地址与实际访问地址不一致 | 登录后一直跳回登录页 | 反向代理必须正确传递 `X-Forwarded-Proto` / `X-Forwarded-Host`；登记的访问地址要与用户实际输入的地址完全一致，不能混用 IP 与域名。**接入自检会直接报出这一项** |
| 强制下线不彻底 | 离职人员在 token 有效期内仍可操作 | 见第七节，两步都要做 |
| Secret 泄漏 | 他人可冒充 Sustain 换取身份 | 走环境变量或配置中心注入；平台详情页可随时重新生成，旧值立即失效 |
| `person_id` 对齐不全 | 部分人员首次登录需要自助绑定 | 不再是阻塞问题，用户自己即可完成；只需在并存阶段观察绑定成功率 |

## 十一、工作量参考

以熟悉 Sustain 代码的开发为基准，不含数据对齐的人工核对时间：

| 事项 | 估时 |
| --- | --- |
| 平台侧登记 | 0.5 小时 |
| 后端：一个 `MedicalIdentityBridge` 实现 + `loginBySso` + 实体与迁移 | 0.5 天 |
| 前端：登录页改造、绑定页、两个路由、store | 0.5 到 1 天 |
| `person_id` 回填脚本（可只覆盖能确定的部分） | 0.5 天 |
| 联调与验收清单逐项跑通 | 0.5 到 1 天 |

**合计约 2 到 3 天。** 相比接入组件升级前的估算（后端 1 到 2 天写 `SsoClient` 与两个端点），减少的部分正是最容易写错的协议实现；数据对齐从「最大不确定项」降级为「并存阶段的观察项」，因为剩余人员可以自助完成。

---

参考：统一认证的完整接入文档见 `docs/INTEGRATION.md`（桥接模式见第十二节）；可运行的参考实现见 `samples/legacy`，它演示的正是本方案的完整链路——登录页、绑定页、一套模拟的旧账号体系。在管理平台登记完成后，系统会自动生成一份填好真实 `client-id`、`issuer` 的 Sustain 专属版本。
