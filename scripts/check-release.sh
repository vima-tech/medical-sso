#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

allow_dirty=false
[[ "${1:-}" == "--allow-dirty" ]] && allow_dirty=true

if [[ "$allow_dirty" != true && -n "$(git status --porcelain)" ]]; then
  echo "工作树不是干净状态；发布产物必须对应一个可追溯提交。" >&2
  exit 1
fi
if rg -n '0\.1\.0-SNAPSHOT|<version>[^<]*SNAPSHOT</version>' --glob '!**/target/**' --glob '!docs/RELEASE.md'; then
  echo "仍存在 SNAPSHOT 版本，不能发布。" >&2
  exit 1
fi

bash -n scripts/*.sh
python3 -m json.tool deploy/keycloak/realm/medical-realm.json >/dev/null
if command -v docker >/dev/null 2>&1; then
  docker compose config --quiet
  docker compose --env-file .env.production.example -f compose.prod.yml config --quiet
elif command -v podman >/dev/null 2>&1; then
  podman compose config >/dev/null
  podman compose --env-file .env.production.example -f compose.prod.yml config >/dev/null
fi
mvn -Dmaven.repo.local=.m2/repository clean verify
echo "发布门禁通过。"
