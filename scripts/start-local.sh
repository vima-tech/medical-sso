#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if command -v docker >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v podman >/dev/null 2>&1; then
  compose=(podman compose)
else
  echo "未找到 Docker 或 Podman，请先安装容器运行环境。" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "已从 .env.example 创建 .env。演示环境可直接使用，正式环境请先修改密码。"
fi

dotenv_value() {
  local key="$1"
  local line
  while IFS= read -r line; do
    line="${line%$'\r'}"
    if [[ "$line" == "$key="* ]]; then
      printf '%s' "${line#*=}"
      return
    fi
  done < .env
}

platform_port="${PLATFORM_PORT:-$(dotenv_value PLATFORM_PORT)}"
platform_port="${platform_port:-18081}"
platform_admin_password="${SSO_PLATFORM_ADMIN_PASSWORD:-$(dotenv_value SSO_PLATFORM_ADMIN_PASSWORD)}"
platform_admin_password="${platform_admin_password:-Admin@123456}"
admin_client_secret="${SSO_ADMIN_CLIENT_SECRET:-$(dotenv_value SSO_ADMIN_CLIENT_SECRET)}"
admin_client_secret="${admin_client_secret:-portal-admin-dev-secret}"

if [[ ! "$platform_port" =~ ^[0-9]+$ ]]; then
  echo "PLATFORM_PORT 必须是数字端口。" >&2
  exit 1
fi

# 对外地址以 .env 的 SSO_PUBLIC_URL 为准：容器里的认证内核和平台都用它推导 issuer，
# 脚本这边必须用同一个值，否则播种和就绪探测打的是另一个地址。
public_url="${SSO_PUBLIC_URL:-$(dotenv_value SSO_PUBLIC_URL)}"
public_url="${public_url:-http://localhost:${platform_port}}"
issuer="${public_url}/auth/realms/medical"

# 演示子系统仍以 jar 跑在宿主机上，需要先构建。
# 统一身份管理平台不在这里构建——它由 compose 按 medical-portal/Dockerfile 构建成镜像。
echo "构建接入组件与演示子系统..."
mvn -Dmaven.repo.local=.m2/repository -q -DskipTests install

# 数据库、认证内核、网关、统一身份管理平台四个容器。
# 只有网关映射宿主端口，其余三个都在容器网络内部。
echo "启动核心容器（首次会构建平台镜像，需要几分钟）..."
"${compose[@]}" up -d --build
# Podman Compose 对同名标签不会自动替换既有容器；显式重建门户，避免出现
# “镜像已经更新，但 18081 仍运行旧镜像摘要”的假更新。
"${compose[@]}" up -d --force-recreate --no-deps portal

echo "等待认证内核就绪..."
ready=false
for _ in $(seq 1 120); do
  if curl -fsS "${issuer}/.well-known/openid-configuration" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done

if [[ "$ready" != "true" ]]; then
  echo "认证内核未在 120 秒内就绪，请查看容器日志。" >&2
  exit 1
fi

# Realm 导入文件是生产就绪的，本机演示需要额外装载示例数据
echo "装载演示数据..."
PLATFORM_URL="$public_url" \
SSO_PLATFORM_ADMIN_PASSWORD="$platform_admin_password" \
SSO_ADMIN_CLIENT_SECRET="$admin_client_secret" \
./scripts/seed-demo.sh

demo_pid=""
demo_boot2_pid=""
demo_legacy_pid=""
gateway_pid=""
upstream_pid=""
cleanup() {
  for pid in "$demo_pid" "$demo_boot2_pid" "$demo_legacy_pid" "$gateway_pid" "$upstream_pid"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

SSO_ISSUER="$issuer" \
java -jar medical-sso-demo/target/medical-sso-demo-0.1.0.jar &
demo_pid=$!
SSO_ISSUER="$issuer" \
java -jar medical-sso-demo-boot2/target/medical-sso-demo-boot2-0.1.0.jar &
demo_boot2_pid=$!
# 已有账号体系的子系统：保留原登录，桥接统一身份，支持自助绑定
SSO_ISSUER="$issuer" \
java -jar medical-sso-demo-legacy/target/medical-sso-demo-legacy-0.1.0.jar &
demo_legacy_pid=$!
# 「改不动的系统」演示：一个对统一认证一无所知的上游，前面挂接入网关
python3 scripts/demo-upstream.py &
upstream_pid=$!
SSO_ISSUER="$issuer" \
java -jar medical-sso-gateway/target/medical-sso-gateway-0.1.0.jar &
gateway_pid=$!

echo
echo "统一身份管理平台：${public_url}"
echo "  平台管理员登录后进管理平台，业务人员登录后进应用门户"
echo "接入示例（JDK 17）：      http://localhost:8082"
echo "接入示例（JDK 8）：       http://localhost:8083"
echo "已有登录体系（桥接）：    http://localhost:8084   本地账号 zs / old-pass-1"
echo "改不动的系统（网关）：    http://localhost:8085   业务系统零改动"
echo
echo "平台管理员：sso-admin / ${platform_admin_password}"
echo "演示人员：  zhangsan / Demo@123456"
echo
echo "认证内核 Keycloak 不对外暴露端口，其自带管理控制台已在引擎层面关闭。"
echo "按 Ctrl+C 停止演示子系统；四个核心容器用 scripts/stop-local.sh 停止。"

wait -n "$demo_pid" "$demo_boot2_pid" "$demo_legacy_pid" "$gateway_pid"
