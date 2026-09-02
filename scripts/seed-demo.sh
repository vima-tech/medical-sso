#!/usr/bin/env bash
# 装载本地演示数据。
#
# Realm 导入文件默认是生产就绪的：只有平台管理员和平台自身的客户端，
# 没有演示人员、演示业务系统和机构骨架。上线时直接导入即可，不会带进演示账号。
#
# 本脚本把演示数据装回去，仅供本机演示和端到端验证使用，不要在生产环境执行。
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

PLATFORM_URL="${PLATFORM_URL:-http://localhost:18081}"
AUTH="$PLATFORM_URL/auth"
ADMIN_CLIENT="${SSO_ADMIN_CLIENT_ID:-medical-portal-admin}"
ADMIN_SECRET="${SSO_ADMIN_CLIENT_SECRET:-portal-admin-dev-secret}"

if [[ "${ALLOW_DEMO_SEED:-}" != "yes" && "$PLATFORM_URL" != http://localhost:* ]]; then
  echo "拒绝对非本机地址装载演示数据：$PLATFORM_URL" >&2
  echo "确认这是演示环境后，用 ALLOW_DEMO_SEED=yes 重新执行。" >&2
  exit 1
fi

echo "等待认证内核就绪..."
for _ in $(seq 1 120); do
  curl -fsS "$AUTH/realms/medical/.well-known/openid-configuration" >/dev/null 2>&1 && break
  sleep 1
done

python3 - "$AUTH" "$ADMIN_CLIENT" "$ADMIN_SECRET" <<'PY'
import json, sys, urllib.request, urllib.parse, urllib.error

AUTH, CLIENT, SECRET = sys.argv[1], sys.argv[2], sys.argv[3]

def call(method, path, token=None, body=None, form=None):
    data = None; headers = {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(AUTH + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            content_type = response.headers.get("Content-Type", "")
            return response.status, (json.loads(raw) if raw and content_type.startswith("application/json") else raw)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()

_, token_response = call("POST", "/realms/medical/protocol/openid-connect/token",
                         form={"grant_type": "client_credentials",
                               "client_id": CLIENT, "client_secret": SECRET})
token = token_response["access_token"]

def client_uuid(client_id):
    _, found = call("GET", f"/admin/realms/medical/clients?clientId={client_id}", token)
    return found[0]["id"] if found else None

# ---------- 机构与科室 ----------
_, groups = call("GET", "/admin/realms/medical/groups", token)
org_id = next((g["id"] for g in groups if g["name"].startswith("H001-")), None)
if org_id is None:
    call("POST", "/admin/realms/medical/groups", token,
         body={"name": "H001-第一人民医院", "attributes": {"org_code": ["H001"]}})
    _, groups = call("GET", "/admin/realms/medical/groups", token)
    org_id = next(g["id"] for g in groups if g["name"].startswith("H001-"))
    print("  建立机构 第一人民医院")
_, children = call("GET", f"/admin/realms/medical/groups/{org_id}/children", token)
existing = {c["name"] for c in children}
for name, code in (("D001-内科", "D001"), ("D002-检验科", "D002")):
    if name not in existing:
        call("POST", f"/admin/realms/medical/groups/{org_id}/children", token,
             body={"name": name, "attributes": {"dept_code": [code]}})
        print(f"  建立科室 {name}")

# ---------- 演示业务系统 ----------
DEMOS = [
    ("medical-demo", "Spring Boot 接入示例", "http://localhost:8082", "demo-dev-secret", "boot3"),
    ("medical-demo-boot2", "JDK 8 子系统接入示例", "http://localhost:8083", "demo-boot2-dev-secret", "boot2"),
]
for client_id, name, base, secret, stack in DEMOS:
    if client_uuid(client_id):
        continue
    call("POST", "/admin/realms/medical/clients", token, body={
        "clientId": client_id, "name": name, "enabled": True, "protocol": "openid-connect",
        "publicClient": False, "secret": secret, "standardFlowEnabled": True,
        "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False, "frontchannelLogout": True,
        "rootUrl": base, "baseUrl": base,
        "redirectUris": [base + "/login/oauth2/code/medical-sso"], "webOrigins": [base],
        "attributes": {"pkce.code.challenge.method": "S256",
                       "post.logout.redirect.uris": base + "/*",
                       "medical.subsystem.stack": stack},
        "defaultClientScopes": ["profile", "roles", "medical-profile"],
        "protocolMappers": [{"name": client_id + "-audience", "protocol": "openid-connect",
                             "protocolMapper": "oidc-audience-mapper", "consentRequired": False,
                             "config": {"included.client.audience": client_id, "id.token.claim": "false",
                                        "access.token.claim": "true", "introspection.token.claim": "true"}}],
    })
    uuid = client_uuid(client_id)
    call("POST", f"/admin/realms/medical/clients/{uuid}/roles", token,
         body={"name": "access", "description": "允许进入 " + name})
    if client_id == "medical-demo":
        call("POST", f"/admin/realms/medical/clients/{uuid}/roles", token,
             body={"name": "his-user", "description": "示例业务角色"})
    print(f"  登记演示业务系统 {name}")

# ---------- 演示人员 ----------
_, found = call("GET", "/admin/realms/medical/users?username=zhangsan", token)
if not found:
    call("POST", "/admin/realms/medical/users", token, body={
        "username": "zhangsan", "enabled": True, "emailVerified": True, "firstName": "张三",
        "attributes": {"full_name": ["张三"], "person_id": ["P000123"], "employee_no": ["10086"],
                       "org_code": ["H001"], "dept_code": ["D001"],
                       "org_codes": ["H001"], "dept_codes": ["D001"]},
        "credentials": [{"type": "password", "value": "Demo@123456", "temporary": False}],
        "groups": ["/H001-第一人民医院/D001-内科"],
    })
    print("  建立演示人员 张三")
_, found = call("GET", "/admin/realms/medical/users?username=zhangsan", token)
user_id = found[0]["id"]
_, realm_roles = call("GET", "/admin/realms/medical/roles", token)
grant = [r for r in realm_roles if r["name"] in ("doctor", "organization-admin")]
call("POST", f"/admin/realms/medical/users/{user_id}/role-mappings/realm", token, body=grant)
for client_id, roles in (("medical-portal", ["access"]),
                         ("medical-demo", ["access", "his-user"]),
                         ("medical-demo-boot2", ["access"])):
    uuid = client_uuid(client_id)
    if not uuid:
        continue
    _, available = call("GET", f"/admin/realms/medical/clients/{uuid}/roles", token)
    wanted = [r for r in available if r["name"] in roles]
    if wanted:
        call("POST", f"/admin/realms/medical/users/{user_id}/role-mappings/clients/{uuid}", token, body=wanted)

# 演示环境把平台管理员的一次性密码换成固定密码，省去每次改密
_, admins = call("GET", "/admin/realms/medical/users?username=sso-admin", token)
if admins:
    admin = admins[0]
    call("PUT", f"/admin/realms/medical/users/{admin['id']}/reset-password", token,
         body={"type": "password", "value": "Admin@123456", "temporary": False})
    # 生产 Realm 要求首次登录改密，演示环境去掉这个动作省去每次改密
    admin["requiredActions"] = []
    call("PUT", f"/admin/realms/medical/users/{admin['id']}", token, body=admin)
    print("  平台管理员密码设为演示用固定密码")

print("演示数据装载完成。")
PY
