#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$project_dir"
source "$project_dir/scripts/lib/compose.sh"
env_file="${1:-$SSO_ENV_PROD}"

if [[ ! -f "$env_file" ]]; then
  echo "未找到 $env_file。" >&2
  exit 1
fi

while IFS= read -r line; do
  line="${line%$'\r'}"
  [[ -z "$line" || "$line" == \#* ]] && continue
  key="${line%%=*}"
  value="${line#*=}"
  if [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ && -z "${!key:-}" ]]; then
    export "$key=$value"
  fi
done < "$env_file"

required=(SSO_HOSTNAME POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD
  KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD
  SSO_PLATFORM_ADMIN_PASSWORD SSO_ADMIN_CLIENT_SECRET SSO_PORTAL_CLIENT_SECRET
  TLS_CERT_FILE TLS_KEY_FILE REALM_IMPORT_DIR)
for key in "${required[@]}"; do
  value="${!key:-}"
  if [[ -z "$value" || "$value" == replace-with-* || "$value" == *example* ]]; then
    echo "$key 尚未填写生产值。" >&2
    exit 1
  fi
done

for key in POSTGRES_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD SSO_PLATFORM_ADMIN_PASSWORD \
  SSO_ADMIN_CLIENT_SECRET SSO_PORTAL_CLIENT_SECRET; do
  value="${!key}"
  if [[ ${#value} -lt 16 ]]; then
    echo "$key 至少需要 16 位。" >&2
    exit 1
  fi
done

if [[ "$SSO_ADMIN_CLIENT_SECRET" == "$SSO_PORTAL_CLIENT_SECRET" ]]; then
  echo "管理服务 Secret 与门户登录 Secret 必须不同。" >&2
  exit 1
fi
if [[ ! -r "$TLS_CERT_FILE" || ! -r "$TLS_KEY_FILE" ]]; then
  echo "TLS 证书或私钥不可读。" >&2
  exit 1
fi
if [[ ! -r "$REALM_IMPORT_DIR/medical-realm.json" ]]; then
  echo "生产 Realm 尚未生成，请先执行 scripts/release/render-production-realm.sh。" >&2
  exit 1
fi

sso_compose_detect || exit 1
# 两种运行时都用重定向而不是 --quiet：podman compose 不认这个参数，
# 而重定向本来就够了，退出码照样能判断配置是否有效。
sso_compose_prod "$env_file" config >/dev/null

echo "生产部署预检通过。"
