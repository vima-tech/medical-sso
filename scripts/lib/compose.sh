#!/usr/bin/env bash
# 容器编排调用的唯一出处。由 scripts/ 下的其它脚本 source，不要直接执行。
#
# 抽出来的原因：compose 文件收进 deploy/ 之后，「compose 文件路径 + env 文件路径 +
# 容器运行时探测」这三件事有七个脚本要写得完全一致，散着写迟早漂移。
#
# 项目名不用在这里操心——deploy/compose.yml 与 deploy/compose.prod.yml 都写了顶层
# name:（medical-sso / medical-sso-prod），容器名不随 compose 文件位置或目录名变化。

SSO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSO_COMPOSE_DEV="$SSO_ROOT/deploy/compose.yml"
SSO_COMPOSE_PROD="$SSO_ROOT/deploy/compose.prod.yml"
SSO_ENV_DEV="$SSO_ROOT/deploy/.env"
SSO_ENV_DEV_EXAMPLE="$SSO_ROOT/deploy/.env.example"
SSO_ENV_PROD="$SSO_ROOT/deploy/.env.production"
SSO_ENV_PROD_EXAMPLE="$SSO_ROOT/deploy/.env.production.example"

# 探测容器运行时，结果写入数组 sso_compose。没装则报错并返回 1。
sso_compose_detect() {
  if command -v docker >/dev/null 2>&1; then
    sso_compose=(docker compose)
  elif command -v podman >/dev/null 2>&1; then
    sso_compose=(podman compose)
  else
    echo "未找到 Docker 或 Podman，请先安装容器运行环境。" >&2
    return 1
  fi
}

# 本机开发栈。用法：sso_compose_dev up -d --build
# .env 不存在时不传 --env-file：compose 对指定却不存在的 env 文件是直接报错，
# 而 status/logs 这类只读子命令在没有 .env 时也应该能跑。
sso_compose_dev() {
  local env_args=()
  [[ -f "$SSO_ENV_DEV" ]] && env_args=(--env-file "$SSO_ENV_DEV")
  "${sso_compose[@]}" ${env_args[@]+"${env_args[@]}"} -f "$SSO_COMPOSE_DEV" "$@"
}

# 生产栈。第一个参数是 env 文件路径，其余原样透传。
sso_compose_prod() {
  local env_file="$1"
  shift
  "${sso_compose[@]}" --env-file "$env_file" -f "$SSO_COMPOSE_PROD" "$@"
}
