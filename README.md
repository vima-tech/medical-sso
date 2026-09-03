# 医共体统一身份认证

基于 Keycloak 26.7.3 的内网单点登录 MVP，包含统一登录、应用门户、统一人员字段、系统级角色隔离、子系统自助登记、按子系统生成的对接文档，以及 JDK 17 和 JDK 8 两套 Spring Boot 接入组件和可运行 Demo。

## 项目组成

仓库按「这东西给谁用」分三层，不按技术栈分。**七个 Maven 模块里只有一个进生产镜像**，
其余要么是交付给子系统的组件，要么是文档的配套样例：

| 目录 | 是什么 | 进生产吗 |
| --- | --- | --- |
| `platform/portal` | **统一身份管理平台**，本项目的产品本体。机构科室、人员、授权中心、业务系统登记、对接文档生成、审计记录都在这里 | ✅ 唯一进镜像的应用 |
| `integration/starter-boot3` | 接入组件，Spring Boot 3.x / JDK 17+ | 交付给子系统，自身不部署 |
| `integration/starter-boot2` | 接入组件，Spring Boot 2.3-2.7 / JDK 8。子系统以这一套为主 | 交付给子系统，自身不部署 |
| `integration/access-proxy` | 接入网关，替改不动的系统完成登录，身份以请求头注入，业务系统零改动 | 交付给子系统，自身不部署 |
| `samples/boot3` | 标准接入最小示例（JDK 17），含页面和 `/api/me` | 仅文档配套 |
| `samples/boot2` | 标准接入最小示例（JDK 8） | 仅文档配套 |
| `samples/legacy` | 已有账号体系的桥接示例：保留原登录，支持自助绑定 | 仅文档配套 |
| `deploy/` | 运行时资产：compose 编排、镜像定义、Realm 初始化数据、登录主题、网关配置、`.env` 模板 | — |
| `docs/` | 接入、管理员操作、生产部署文档 | — |
| `scripts/` | 脚本，按用途分三类，见下表 | — |

`samples/` 三个模块加起来不到 900 行，就是 `docs/INTEGRATION.md` 里「完整可运行代码见 XXX」
指向的那份代码，同时充当两套接入组件的集成验证。它们不进任何部署产物。

> **目录名和 Maven 坐标是两回事。** `integration/starter-boot3` 的 artifactId 仍然是
> `medical-sso-spring-boot-starter`，`samples/boot3` 仍然是 `medical-sso-demo`。
> 子系统 pom 里写的是坐标，不受仓库目录调整影响。
>
> 唯一改过坐标的是接入网关：`medical-sso-gateway` → `medical-sso-access-proxy`，
> 为的是和 `deploy/compose.yml` 里那个 nginx 的 `gateway` 服务区分开——后者是整套平台
> 对外的唯一入口，两者毫无关系。请求头 `X-Medical-Gateway-Token`、配置前缀
> `medical.sso.gateway.*` 是与已接入子系统的线上契约，**没有**随改名变动。

### 脚本一览

`scripts/` 根下只放**日常入口**，其余按用途分目录。绝大多数人只会用到第一行：

| 脚本 | 什么时候用 |
| --- | --- |
| `scripts/sso.sh` / `sso.ps1` | **日常唯一入口**：`start` / `stop` / `status` / `logs`。Windows 双击根目录两个 `.cmd` 即可 |
| `scripts/demo/start-local.sh` | 跑带四个示例子系统的完整演示环境（开发者看接入效果用，仅 Linux / macOS） |
| `scripts/demo/seed-demo.sh` | 装载演示机构、人员和业务系统。对非 `localhost` 地址会直接拒绝，**生产禁用** |
| `scripts/demo/upstream.py` | 演示用的假上游，配合接入网关演示「改不动的系统」 |
| `scripts/release/check-release.sh` | 发布门禁，CI 每次推送都跑：脏工作树、SNAPSHOT、脚本语法、compose 配置、全量测试 |
| `scripts/release/preflight-production.sh` | 生产预检：必填项、密码长度、证书可读、生产 Realm 已生成 |
| `scripts/release/render-production-realm.sh` | 把真实 Secret 注入 Realm，生成生产导入文件 |
| `scripts/release/smoke-test.sh` | 上线后冒烟，含管理控制台防枚举断言 |
| `scripts/release/backup-postgres.sh` / `restore-postgres.sh` | 生产库备份与恢复（恢复强制 `--confirm`） |
| `scripts/lib/*.sh` | 被上面这些 `source` 的共用片段，**不要直接执行** |

## 子系统怎么接入

四种方式，按系统情况选一种，不要挨个看：

| 你的系统 | 接入方式 | 改动量 |
| --- | --- | --- |
| Spring Boot，没有自己的登录体系 | 标准接入 | 两段配置 + 一个安全配置类 |
| Spring Boot，已有账号密码登录，且要两种登录并存 | 桥接模式 | 实现一个接口，共两个方法 |
| 不是 Java、没有源码、或代码动不了 | 接入网关 | **零改动**，前面挂一个网关进程 |
| 只提供接口、不做页面 | 资源服务 | 一段配置 |

在管理平台登记业务系统时选好接入方式，页面会直接生成填好真实参数的对接说明。
配完点「自检」，平台会逐项检查配置并给出修复动作。详见 `docs/INTEGRATION.md`。

## 快速开始

### 第一步：装一个容器运行环境

| 你的系统 | 装什么 |
| --- | --- |
| Windows | [Docker Desktop](https://www.docker.com/products/docker-desktop/)，装完打开它，等托盘图标不再转圈 |
| macOS | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| Linux | Docker 或 Podman |

**只需要这一样。** 不用装 Java，不用装 Maven，不用装数据库——它们都在镜像里。

### 第二步：启动

| 你的系统 | 怎么做 |
| --- | --- |
| Windows | 双击仓库根目录的 **`启动平台.cmd`** |
| Linux / macOS | 终端执行 **`./scripts/sso.sh start`** |

第一次启动要构建平台镜像，约几分钟；之后只要没改过仓库里的文件，重启都是秒级。看到「统一身份认证平台已就绪」就成了。

（改过代码或文档后，下次启动会重新构建镜像，又要几分钟——这是正常的，不是卡住了。）

### 第三步：第一次登录

浏览器打开 **http://localhost:18081**，用下面这组账号登录：

```
账号：sso-admin
密码：Admin@123456
```

**登录后系统会立刻要求你改密码。** 这是刻意设计的，不是出错了——初始密码写在配置文件里，人人可见，所以它只能用一次。改完密码就进入统一身份管理平台，可以开始建机构、建人员、登记业务系统。

后面怎么用，看[管理员操作指南](docs/ADMIN-GUIDE.md)。

### 停止

| 你的系统 | 怎么做 |
| --- | --- |
| Windows | 双击 **`停止平台.cmd`** |
| Linux / macOS | **`./scripts/sso.sh stop`** |

数据存在容器卷里，停止不会丢，下次启动接着用。

### 其它几个命令

```bash
./scripts/sso.sh status    # 看运行状态和对外入口是否可访问
./scripts/sso.sh logs      # 看日志，按 Ctrl+C 退出
```

Windows 上对应 `.\scripts\sso.ps1 status` 和 `.\scripts\sso.ps1 logs`。

**日常只需要这一个脚本。** 底下跑着数据库、认证内核、网关和管理平台四个容器，但运维不需要知道它们各自叫什么。

编排文件放在 `deploy/` 下，所以在仓库根目录直接敲 `docker compose up` 是找不到它的。
要手工调用就显式指定：`docker compose -f deploy/compose.yml ...`。

### 需要改配置吗？

**本地试用不用改任何东西。** 第一次启动会自动从 `deploy/.env.example` 生成 `deploy/.env`，里面的密码都是演示用的默认值，直接能跑。

正式上线是另一套配置文件（`deploy/.env.production`），密码、域名、证书都必须换成真实值，一项都不能省——生产编排会逐项校验，缺哪项就报哪项。详见[生产部署与安全清单](docs/DEPLOYMENT.md)。

## 跑完整演示环境

上面启动的是一个干净平台：只有平台管理员，没有演示人员和演示业务系统。

想看四种接入方式的实际效果，可以跑完整演示——它会额外装载演示数据，并在宿主机上拉起四个示例子系统。**这一步是给开发者看接入效果的，额外需要 Java 17、Maven 3.9+ 和 python3，且目前只支持 Linux / macOS：**

```bash
chmod +x scripts/*.sh scripts/demo/*.sh scripts/release/*.sh
./scripts/demo/start-local.sh
```

启动后访问：

| 服务 | 地址 |
| --- | --- |
| 统一身份管理平台 | http://localhost:18081 |
| 接入示例（JDK 17） | http://localhost:8082 |
| 接入示例（JDK 8） | http://localhost:8083 |
| 已有登录体系（桥接模式） | http://localhost:8084 |
| 改不动的系统（接入网关） | http://localhost:8085 |

演示环境里 `sso-admin` 的密码取自 `deploy/.env` 的 `SSO_PLATFORM_ADMIN_PASSWORD`，并且**不强制改密**，方便反复演示。演示人员用 `zhangsan / Demo@123456`。

停止四个示例子系统在启动终端按 `Ctrl+C`，停止平台用 `./scripts/sso.sh stop`。

**对外只有 18081 这一个端口。** 认证内核 Keycloak 不映射任何宿主端口，浏览器经网关的 `/auth` 前缀访问它的登录页；它自带的管理控制台已用 `--features-disabled=admin` 在引擎层面关闭，不存在第二个管理入口。

Realm 导入文件是**生产就绪**的：只有平台管理员和平台自身的客户端，没有演示人员、演示业务系统和机构骨架。演示数据由 `scripts/demo/seed-demo.sh` 单独装载，`scripts/demo/start-local.sh` 会自动执行；**上线时不要执行该脚本**。

### 三个账号，用途完全不同，不要混用

| 账号 | 登录哪里 | 用途 |
| --- | --- | --- |
| `sso-admin` | 统一身份管理平台 http://localhost:18081 | **平台管理员**。初始密码 `Admin@123456`，首次登录强制改密；演示环境改用 `deploy/.env` 里的密码且不强制改密 |
| `zhangsan / Demo@123456` | 应用门户和各业务系统 | **演示医生**，仅由 `seed-demo.sh` 装载，生产环境不存在 |
| `deploy/.env` 里的 `KC_BOOTSTRAP_ADMIN_*` | 无图形界面可登录 | **引导管理员**，只存在于 `master` Realm，仅供底层排查 |

引导管理员没有界面可登：Keycloak 控制台已关闭，在业务登录页上用它也一定登不进去，会提示「工号或密码错误」——这是预期行为，它不是业务账号。日常运维一律用 `sso-admin`。

## 统一身份管理平台

`18081` 是本项目的核心产品，不是演示门户。医院管理员在这里完成全部日常运维，界面只用业务语言，不出现 Realm、Client、Scope、Mapper 等术语。

```text
                18081
                  ↓  按角色分流
    ┌─────────────┴─────────────┐
平台管理员                    业务人员
    ↓                            ↓
统一身份管理平台 /admin      应用门户 /apps
 ├── 机构与科室               只列出本人有权进入的系统
 ├── 人员管理
 └── 业务系统
    ↓ Admin API
Keycloak 认证内核（不对外暴露端口，无管理控制台）
```

访问 `18081` 无需记路径：平台管理员登录后直接落到管理平台，业务人员落到应用门户。两边顶栏互有入口。

Keycloak 只作为认证内核运行在后台，其自带控制台仅用于引擎故障排查，不是运维入口。

| 模块 | 能做什么 |
| --- | --- |
| 机构与科室 | 维护机构、科室及编码；删除前校验下属科室与在职人员 |
| 人员管理 | 建档、编辑、调科、重置密码、停用（同时注销会话）、强制下线、批量导入 |
| 授权中心 | 「人员 × 业务系统」矩阵，按科室批量授权与收回 |
| 业务系统 | 登记、改名换址、系统角色维护、重新生成 Secret、停用与删除；生成专属对接文档 |
| 通用身份 | 维护医生、护士等跨系统岗位标识 |
| 审计记录 | 登录成功失败、退出、改密，以及管理员的全部增删改；只读不可删 |

界面术语对照：

| 底层概念 | 管理员看到的名称 |
| --- | --- |
| Client | 业务系统 |
| Client Role `access` | 可访问的业务系统 |
| Realm Role | 通用身份 |
| Group | 机构 / 科室 |
| Redirect URI | 系统回调地址（自动推导，不需填写） |

## 接入一个子系统

用具有 `sso-platform-admin` 身份的管理员账号登录管理平台，进「业务系统」登记，只填系统名称、系统编码、访问地址和接入方式四项。提交后平台自动建立认证关系、强制 PKCE、推导回调地址并创建访问权限，页面随即给出填好真实参数的依赖、配置和代码，逐段复制到业务系统即可。Client Secret 只展示一次，配置片段里只留环境变量占位，可以安全提交进代码库。

最后在管理平台「授权中心」中为人员分配该系统的访问权限。详见 [管理员操作指南](docs/ADMIN-GUIDE.md)。

## 统一身份返回值

子系统通过 `MedicalUserMapper` 得到稳定的业务对象：

```json
{
  "subject": "Keycloak 用户 UUID",
  "personId": "P000123",
  "employeeNo": "10086",
  "username": "zhangsan",
  "name": "张三",
  "organizationCode": "H001",
  "departmentCode": "D001",
  "realmRoles": ["doctor"],
  "clientRoles": ["access", "his-user"]
}
```

`personId` 是跨系统稳定人员标识。`subject` 只作为认证平台内部标识。患者信息、诊疗数据、身份证号等敏感数据不得放入 Token。

两套接入组件返回的对象方法名一致，业务代码在 JDK 8 和 JDK 17 之间可以直接互换。

## 文档入口

- [Spring Boot 完整接入文档](docs/INTEGRATION.md) —— 四种接入方式的完整说明与代码
- [用户、机构、科室和角色管理](docs/ADMIN-GUIDE.md) —— 管理员日常操作
- [生产部署与安全清单](docs/DEPLOYMENT.md) —— 上线拓扑、安全边界、必填配置
- [发布、备份与回滚](docs/RELEASE.md) —— 交付候选的发布步骤与回滚预案
- [Sustain 接入实施方案](docs/SUSTAIN-ONBOARDING.md) —— 具体子系统的桥接模式落地样板，可作为同类系统的参考

## 验证

```bash
mvn -Dmaven.repo.local=.m2/repository test
```

交付候选还应执行 `./scripts/release/check-release.sh`；本地未提交改动可用
`./scripts/release/check-release.sh --allow-dirty` 做同等构建检查。生产部署、备份恢复、验证和回滚步骤见
[Release Package](docs/RELEASE.md)。

当前 `deploy/compose.yml` 使用开发模式和演示密钥，只用于本机或隔离测试网。生产部署必须按照部署文档替换。
