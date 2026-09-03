#!/usr/bin/env bash
# 本机 .env 的读取与自举。由 scripts/ 下的脚本 source，不要直接执行。
#
# 抽出来的原因：sso.sh（运维入口）和 demo/start-local.sh（演示环境）都要读同一份
# deploy/.env，此前两边各写了一份逐字相同的 dotenv_value 和 .env 自举片段。
#
# 依赖 lib/compose.sh 提供的 SSO_ENV_DEV / SSO_ENV_DEV_EXAMPLE，请先 source 它。

# 从 env 文件里读一个键的值。
# 文件不存在时返回空而不是报错——status 这类只读命令可能在 .env 还没生成时就被调用。
dotenv_value() {   # $1=键名  $2=env 文件（默认本机 deploy/.env）
  local key="$1"
  local file="${2:-$SSO_ENV_DEV}"
  local line
  [[ -f "$file" ]] || return 0
  while IFS= read -r line; do
    line="${line%$'\r'}"
    if [[ "$line" == "$key="* ]]; then
      printf '%s' "${line#*=}"
      return
    fi
  done < "$file"
}

# deploy/.env 不存在就从模板生成一份。
sso_ensure_env() {
  [[ -f "$SSO_ENV_DEV" ]] && return 0
  cp "$SSO_ENV_DEV_EXAMPLE" "$SSO_ENV_DEV"
  echo "已从 deploy/.env.example 创建 deploy/.env。演示环境可直接使用，正式环境请先修改密码。"
}

# 平台对外地址。容器里的认证内核和平台都按它推导 issuer，脚本这边必须取到同一个值，
# 否则就绪探测和演示数据播种打的是另一个地址。
# 优先级：环境变量 > deploy/.env > 按 PLATFORM_PORT 拼 localhost > 18081。
sso_public_url() {
  local url port
  url="${SSO_PUBLIC_URL:-$(dotenv_value SSO_PUBLIC_URL)}"
  if [[ -n "$url" ]]; then
    printf '%s' "$url"
    return
  fi
  port="${PLATFORM_PORT:-$(dotenv_value PLATFORM_PORT)}"
  printf 'http://localhost:%s' "${port:-18081}"
}
