#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$project_dir"
source "$project_dir/scripts/lib/compose.sh"

allow_dirty=false
[[ "${1:-}" == "--allow-dirty" ]] && allow_dirty=true

if [[ "$allow_dirty" != true && -n "$(git status --porcelain)" ]]; then
  echo "工作树不是干净状态；发布产物必须对应一个可追溯提交。" >&2
  exit 1
fi
# 必须显式给出搜索路径 "."：rg 不带路径时，只有 stdin 是 TTY 才会去扫当前目录，
# 否则它改去读 stdin——在 CI（stdin 是 /dev/null）会立刻读到 EOF、无匹配、静默放行，
# 这条门禁就成了空转；在管道里跑则会永久挂起。
if ! command -v rg >/dev/null 2>&1; then
  echo "未找到 ripgrep(rg)，无法执行 SNAPSHOT 检查。门禁不允许静默跳过。" >&2
  exit 1
fi
if rg -n '0\.1\.0-SNAPSHOT|<version>[^<]*SNAPSHOT</version>' --glob '!**/target/**' --glob '!docs/RELEASE.md' .; then
  echo "仍存在 SNAPSHOT 版本，不能发布。" >&2
  exit 1
fi

bash -n scripts/*.sh scripts/lib/*.sh scripts/demo/*.sh scripts/release/*.sh
python3 -m json.tool deploy/keycloak/realm/medical-realm.json >/dev/null
# 没装容器运行时就跳过这段，与原来的行为一致：CI 门禁不强制要求本机有 Docker。
if sso_compose_detect 2>/dev/null; then
  sso_compose_dev config >/dev/null
  sso_compose_prod "$SSO_ENV_PROD_EXAMPLE" config >/dev/null
fi
mvn -Dmaven.repo.local=.m2/repository clean verify
echo "发布门禁通过。"
