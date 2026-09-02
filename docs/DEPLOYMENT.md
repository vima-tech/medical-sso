# 生产部署与安全清单

`compose.yml` 是开发环境，不可原样用于生产。生产环境至少包含反向代理或负载均衡器、Keycloak、PostgreSQL、备份和监控。

## 推荐拓扑

```text
内网浏览器
    |
HTTPS 统一域名（唯一对外入口）
    |
反向代理 / 负载均衡
    |-- /auth/*  --> Keycloak 认证内核 1-2 节点
    |-- /        --> 统一身份管理平台
                          |
                     生产 PostgreSQL
```

认证内核不对外暴露端口，只能经反向代理的 `/auth` 前缀访问；其自带管理控制台已在引擎层面关闭，生产同样不得开启。

本机开发环境有一处与生产不同：统一身份管理平台以 jar 形式跑在宿主机 `18082`，网关经 `host.containers.internal` 回连它，因此该端口监听在 `0.0.0.0` 而不是回环地址。生产环境应把平台也部署在反向代理之后的内网段，只由代理可达，不要照搬这一点。

首期用户规模较小时可以单 Keycloak 节点，但数据库必须备份。认证中断会影响所有接入系统，新系统正式推广前建议升级为双节点。

## 必须修改

- 使用固定内网域名，例如 `https://sso.intra.example`。
- 使用受信任的内网 CA 或合规证书，全链路 HTTPS。
- 将 `start-dev` 改为生产 `start`，并配置正确的 hostname 和 proxy headers。
- 替换管理员密码、数据库密码、所有 Client Secret。
- Secret 通过容器 Secret 或配置中心注入，不写入 Compose 和 Git。
- PostgreSQL 使用独立持久化存储并执行自动备份。
- Redirect URI 和 Web Origin 使用精确地址，禁止通配符。
- 所有客户端保持 `pkce.code.challenge.method=S256`，新建客户端同样要开启。
- 接口类子系统保持 `medical.sso.require-audience=true`；客户端默认 `fullScopeAllowed=true`，不校验 audience 时其他系统的 Token 可以调本系统接口。
- 保持 `--features-disabled=admin`，不要为了排查方便临时打开 Keycloak 管理控制台。
- 为管理平台单独配置服务账号 Secret（`SSO_ADMIN_CLIENT_SECRET`），不要复用平台登录用的 Secret。
- 复核服务账号权限：`manage-clients`、`view-clients`、`manage-users`、`view-users`、`view-realm`、`manage-realm`、`view-events`、`manage-events`。这是管理平台全部功能所需的最小集合，不要授予 `realm-admin`。
- 开启登录事件、管理员事件、健康检查和指标采集。

## 会话建议

当前演示 Realm 配置：Access Token 5 分钟、SSO 空闲 30 分钟、SSO 最长 8 小时。上线前根据医院安全制度确认。

高风险操作不能只依赖“用户已经登录”。处方、退费、权限分配等操作应在业务系统中增加二次确认或重新认证。

## Realm 导入的两个陷阱

**`--import-realm` 不会覆盖已存在的 Realm。** 数据卷里已经有 `medical` 时，Keycloak 直接跳过导入，改了 `medical-realm.json` 也不会生效，而且不报错。表现是「配置明明改了却没变化」。要让改动生效，只能清掉数据卷重新导入，或用 Admin API 删除该 Realm 后按 JSON 重建：

```bash
./scripts/stop-local.sh              # 停止容器
docker volume rm medical-sso_keycloak-postgres   # 清掉数据卷，容器名前缀按实际项目名
./scripts/start-local.sh             # 重新导入
```

**Realm JSON 里的 `clientScopes` 数组会替换内置 Scope 集合，不是追加。** 只写自定义 Scope 会让 `web-origins`、`acr`、`basic`、`email` 这些内置 Scope 在导入后消失；客户端如果仍在 `defaultClientScopes` 里引用它们，导入时会报找不到，运行时授权请求会因 `Invalid scopes` 被拒。本项目的做法是让客户端只引用 JSON 中实际定义的三个 Scope：`profile`、`roles`、`medical-profile`。新增 Scope 时两边要一起改。

## 子系统登记功能

门户的 `/admin` 区域用服务账号客户端 `medical-portal-admin` 调用 Keycloak Admin API，该账号持有 `manage-clients` 和 `view-clients`，能创建和修改本 Realm 的所有客户端，属于高权限凭据。

- 只授予这两个角色，不要顺手加 `realm-admin`。
- Secret 通过密钥系统注入，与门户登录用的 `medical-portal` Secret 分开管理并分别轮换。
- `/admin` 已限定平台级角色 `sso-platform-admin`。该角色能创建 OAuth 客户端并取得 Client Secret，只授予统一认证平台负责人，不要与机构级 `organization-admin` 合并，并纳入管理员事件审计。
- 不需要自助登记时，用 `PORTAL_ADMIN_ENABLED=false` 关闭整个功能，此时门户不再需要服务账号 Secret。
- 登记页生成的 Client Secret 只展示一次，门户不落库、不写日志。

## 演示数据与生产数据

`deploy/keycloak/realm/medical-realm.json` 是**生产就绪**的导入文件：

- 只有平台管理员 `sso-admin` 和平台自身的两个客户端
- 没有演示人员、演示业务系统，机构科室为空，由管理员在平台上自行建立
- `sso-admin` 带 `UPDATE_PASSWORD`，首次登录必须改密

演示数据由 `scripts/seed-demo.sh` 单独装载，本机 `start-local.sh` 会自动执行。**生产环境不要执行该脚本**；它对非 `localhost` 地址会直接拒绝，需要显式 `ALLOW_DEMO_SEED=yes` 才会继续。

这样安排的原因是失败方向：忘了执行播种脚本，得到的是一个干净的生产环境；反过来如果默认带演示数据、靠上线时手工删除，一旦漏删就是演示账号进生产。

## 审计与留存

Realm 已开启事件记录，随导入生效：

- 登录事件：登录成功、登录失败、退出、修改密码、令牌签发与失败、锁定，保留 30 天
- 管理事件：管理员在平台上的全部增删改，**不记录请求体**（`adminEventsDetailsEnabled=false`），避免把密码等内容写进审计库

管理平台的「审计记录」只读，管理员无法删改。保留期到后由 Keycloak 自行清理。

医院有更长留存要求时，把事件同步到内网日志平台，不要单纯调大 `eventsExpiration`——事件表随之膨胀会拖慢认证内核。

## 数据与隐私

Token 只承载完成认证和粗粒度授权所需字段。不要加入：身份证号、手机号、患者信息、诊疗信息、完整组织通讯录或其他非必要数据。

浏览器 Session Cookie 应启用 Secure、HttpOnly 和合适的 SameSite 策略。反向代理应设置安全响应头并限制管理入口。

## 备份恢复

核心数据位于 PostgreSQL。至少执行：

- 每日全量备份和按需增量备份。
- 定期恢复演练，而不只是检查备份文件存在。
- Realm 配置、主题代码和接入文档进入版本管理。
- Client Secret 由密钥系统备份，不写入 Realm 导出文件长期保存。

开发环境 Realm JSON 中包含演示 Secret 和演示密码，不得直接作为生产备份使用。

## 上线验收

- Keycloak 重启后 Realm、用户和客户端数据正常。
- 单节点故障符合既定可用性目标。
- 数据库备份可恢复。
- 登录失败锁定策略生效。
- Keycloak 未映射任何对外端口，其管理控制台确认不可访问。
- 统一身份管理平台自身端口只对反向代理开放，不直接暴露给用户网段。
- 事件记录已开启，且管理事件未记录请求体。
- 未执行 `scripts/seed-demo.sh`；确认 Realm 中不存在 `zhangsan`、`medical-demo`、`medical-demo-boot2`。
- 平台管理员 `sso-admin` 已完成首次登录改密，或已改用真实管理员账号。
- 所有应用使用 HTTPS 和精确回调地址。
- 所有客户端强制 PKCE S256，且各子系统已显式开启，登录流验证通过。
- 门户服务账号权限仅为 manage-clients 和 view-clients，Secret 与门户登录 Secret 不同。
- `sso-platform-admin` 的授予名单已复核，未混入机构管理员。
- 接口类子系统的 audience 校验已开启，跨系统 Token 调用被拒绝。
- 跨系统单点登录和统一退出验证通过。
- 用户停用后的会话清理流程验证通过。
- 时钟同步正常，Keycloak、应用和数据库时间一致。

## 升级原则

固定 Keycloak 镜像版本，不使用 `latest`。先在测试环境升级并验证 Realm 导入、登录主题、OIDC 登录、角色 Claim 和退出流程，再升级生产环境。升级前必须完成数据库备份。
