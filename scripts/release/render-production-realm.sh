#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$project_dir"
source "$project_dir/scripts/lib/compose.sh"

env_file="${1:-$SSO_ENV_PROD}"
if [[ ! -f "$env_file" ]]; then
  echo "未找到 $env_file，请先复制 deploy/.env.production.example 并填写。" >&2
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

for key in SSO_PLATFORM_ADMIN_PASSWORD SSO_ADMIN_CLIENT_SECRET SSO_PORTAL_CLIENT_SECRET; do
  value="${!key:-}"
  if [[ ${#value} -lt 16 || "$value" == replace-with-* ]]; then
    echo "$key 必须改为至少 16 位的生产值。" >&2
    exit 1
  fi
done

output_dir="${REALM_IMPORT_DIR:-$project_dir/target/production-realm}"
mkdir -p "$output_dir"

python3 - "$project_dir/deploy/keycloak/realm/medical-realm.json" "$output_dir/medical-realm.json" <<'PY'
import json
import os
import sys

source, target = sys.argv[1:]
with open(source, encoding="utf-8") as stream:
    realm = json.load(stream)

secrets = {
    "medical-portal": os.environ["SSO_PORTAL_CLIENT_SECRET"],
    "medical-portal-admin": os.environ["SSO_ADMIN_CLIENT_SECRET"],
}
for client in realm.get("clients", []):
    if client.get("clientId") in secrets:
        client["secret"] = secrets[client["clientId"]]

for user in realm.get("users", []):
    if user.get("username") == "sso-admin":
        user["credentials"] = [{
            "type": "password",
            "value": os.environ["SSO_PLATFORM_ADMIN_PASSWORD"],
            "temporary": True,
        }]
        user["requiredActions"] = ["UPDATE_PASSWORD"]

temporary = target + ".tmp"
with open(temporary, "w", encoding="utf-8") as stream:
    json.dump(realm, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
os.replace(temporary, target)
PY

chmod 600 "$output_dir/medical-realm.json"
echo "已生成生产 Realm：$output_dir/medical-realm.json"
