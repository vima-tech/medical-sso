# Release Package

版本：0.1.0  
发布日期：待定  
关联范围：统一身份管理平台、四种业务系统接入方式、认证内核与统一入口

## Changes

- 管理后台覆盖机构与科室、人员、授权、业务系统、通用身份和审计六个模块。
- 业务系统登记页按接入方式对齐展示，并生成对应对接说明。
- `.env` 明确区分平台管理员密码、引导管理员密码和两个客户端 Secret；本地启动会把配置传入实际进程。
- 管理后台访问认证内核增加 3 秒连接超时和 10 秒读取超时。
- 增加生产 TLS 入口、生产容器编排、Realm 安全渲染、备份恢复、冒烟测试、CI 和依赖更新检查。
- 发布版本由 `0.1.0-SNAPSHOT` 固化为 `0.1.0`。

## Database

Migration：无结构迁移，Keycloak 26.7.3 自行管理数据库结构。  
是否可回滚：可。回滚前必须保留当前 PostgreSQL 备份；若 Keycloak 数据结构发生升级，只能恢复与旧版本同时制作的数据库备份。

## Configuration

生产配置模板：`.env.production.example`。

必须提供：`SSO_HOSTNAME`、数据库账号密码、引导管理员账号密码、平台管理员初始密码、两个不同的客户端 Secret、TLS 证书和私钥绝对路径、生成后的 Realm 目录。

真实 Secret 不得写入 Git 或 Compose。`.env.production` 已被忽略，正式环境仍应优先由密钥系统或编排平台注入。

## Deployment Steps

1. 准备域名、DNS、TLS 证书、服务器时间同步和防火墙规则。
2. 复制 `.env.production.example` 为 `.env.production`，填入真实配置。
3. 执行 `./scripts/render-production-realm.sh .env.production`。
4. 执行 `./scripts/preflight-production.sh .env.production`，检查 Secret、证书、Realm 和编排配置。
5. 执行 `./scripts/check-release.sh`，确认代码来自干净、可追溯的提交。
6. 执行 `docker compose --env-file .env.production -f compose.prod.yml up -d --build`。
7. 执行 `./scripts/smoke-test.sh https://实际域名`。
8. 由平台管理员首次登录并修改初始密码，再验证六个管理模块。
9. 使用测试人员验证登录、授权、停用、强制下线、退出及至少一种真实业务系统接入。
10. 执行一次备份和隔离环境恢复演练，记录恢复耗时与结果。

## Verification

- 自动化：`./scripts/check-release.sh`。
- 在线冒烟：`./scripts/smoke-test.sh https://实际域名`。
- 关键流程：管理员登录、人员建档、系统登记、授权、业务人员单点登录、退出、停用后会话失效。
- 安全检查：`/auth/admin/master/` 与 master Realm 发现地址返回 404；外部只能访问 80/443；所有回调地址精确匹配并启用 PKCE S256。

## Known Issues

- `compose.prod.yml` 是单节点生产基线，不承诺认证内核高可用；若可用性目标要求节点故障无中断，需在客户环境增加负载均衡、多 Keycloak 节点和共享会话/缓存方案后单独验收。
- Realm 导入只在首次建库时生效，不能用它覆盖存量配置。
- 接入网关示例的会话保存在进程内存；多实例部署时需要替换为共享会话存储。
- 域名、证书、真实 Secret、监控告警接收人和备份保留周期必须由交付环境确认，仓库不能代填。

## Rollback

1. 停止新版本入口流量并记录故障时间。
2. 执行 `./scripts/backup-postgres.sh` 保存故障现场数据。
3. 切回上一个已验收的镜像标签和配置版本。
4. 若数据库结构未变化，直接启动旧版本；若发生不兼容升级，执行 `./scripts/restore-postgres.sh <升级前备份> --confirm`。
5. 执行在线冒烟和关键登录流程，确认后恢复流量。

## Delivery Notes

- 生产环境禁止执行 `scripts/seed-demo.sh`，并确认不存在 `zhangsan` 及四个演示客户端。
- 平台管理员、数据库、引导管理员和两个客户端 Secret 应分别保管、分别轮换。
- 上线责任人需补齐：实施负责人、回滚负责人、数据库负责人、安全负责人、上线窗口和观察期。
