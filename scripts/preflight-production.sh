#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
env_file="${1:-.env.production}"

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
  echo "生产 Realm 尚未生成，请先执行 render-production-realm.sh。" >&2
  exit 1
fi

if command -v docker >/dev/null 2>&1; then
  docker compose --env-file "$env_file" -f compose.prod.yml config --quiet
elif command -v podman >/dev/null 2>&1; then
  podman compose --env-file "$env_file" -f compose.prod.yml config >/dev/null
else
  echo "未找到 Docker 或 Podman。" >&2
  exit 1
fi

echo "生产部署预检通过。"
