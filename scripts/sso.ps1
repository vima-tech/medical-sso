<#
.SYNOPSIS
    统一身份认证平台的运维入口（Windows）。

.DESCRIPTION
    底下是四个容器（数据库、认证内核、网关、统一身份管理平台），
    但日常运维只需要认这一个脚本，不需要知道它们各自叫什么。

    不想敲命令的话，直接双击仓库根目录的「启动平台.cmd」。

.EXAMPLE
    .\scripts\sso.ps1 start     启动，等到平台真正可访问才返回
    .\scripts\sso.ps1 stop      停止
    .\scripts\sso.ps1 status    看运行状态
    .\scripts\sso.ps1 logs      看日志，可跟服务名只看其中一个
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Command = '',

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Stop'

# 仓库根目录：本脚本在 scripts\ 下，往上一级就是
$ProjectDir = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectDir

# compose 文件与本机 .env 都在 deploy\ 下，所以每次调用都要显式 -f 指定，
# 不能再依赖「在仓库根目录敲 docker compose 就能找到 compose.yml」。
$ComposeFile = Join-Path $ProjectDir 'deploy\compose.yml'
$EnvFile     = Join-Path $ProjectDir 'deploy\.env'
$EnvExample  = Join-Path $ProjectDir 'deploy\.env.example'

# .env 还不存在时不能传 --env-file：compose 对指定却不存在的 env 文件直接报错，
# 而 status / logs 在没有 .env 的情况下也应该能跑。
function Get-ComposeArgs {
    $composeArgs = @('-f', $ComposeFile)
    if (Test-Path $EnvFile) { $composeArgs = @('--env-file', $EnvFile) + $composeArgs }
    return $composeArgs
}

function Get-ComposeCommand {
    if (Get-Command docker -ErrorAction SilentlyContinue) { return @('docker', 'compose') }
    if (Get-Command podman -ErrorAction SilentlyContinue) { return @('podman', 'compose') }
    Write-Host ""
    Write-Host "没有找到 Docker Desktop（或 Podman）。" -ForegroundColor Red
    Write-Host "请先安装 Docker Desktop 并启动它，再运行本脚本："
    Write-Host "  https://www.docker.com/products/docker-desktop/"
    Write-Host ""
    exit 1
}

function Get-DotEnvValue([string]$Key) {
    if (-not (Test-Path $EnvFile)) { return $null }
    foreach ($line in Get-Content $EnvFile -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("$Key=")) {
            return $trimmed.Substring($Key.Length + 1).Trim()
        }
    }
    return $null
}

function Get-PublicUrl {
    $url = $env:SSO_PUBLIC_URL
    if ([string]::IsNullOrWhiteSpace($url)) { $url = Get-DotEnvValue 'SSO_PUBLIC_URL' }
    if ([string]::IsNullOrWhiteSpace($url)) {
        $port = Get-DotEnvValue 'PLATFORM_PORT'
        if ([string]::IsNullOrWhiteSpace($port)) { $port = '18081' }
        $url = "http://localhost:$port"
    }
    return $url
}

function Test-PlatformReady([string]$Url) {
    try {
        Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
        return $true
    } catch {
        return $false
    }
}

# 先校验子命令再探测容器运行时：敲错命令时该看到用法，而不是「没装 Docker」
if ($Command -notin @('start', 'stop', 'status', 'logs')) {
    Write-Host "用法：.\scripts\sso.ps1 {start|stop|status|logs [服务名]}"
    exit 1
}

$compose = Get-ComposeCommand
$exe = $compose[0]
$sub = $compose[1]

switch ($Command) {
    'start' {
        if (-not (Test-Path $EnvFile)) {
            Copy-Item $EnvExample $EnvFile
            Write-Host "已从 deploy\.env.example 创建 deploy\.env。演示环境可直接使用，正式环境请先修改密码。" -ForegroundColor Yellow
        }

        Write-Host "启动中（首次会构建平台镜像，需要几分钟，请耐心等待）..." -ForegroundColor Cyan
        $composeArgs = Get-ComposeArgs
        # --force-recreate 不能省：compose 在镜像重建后不一定重建已存在的容器，
        # 于是「改完代码再 start」跑的仍是上一版镜像，界面看不到任何变化。
        # 多花的时间只是容器重建，数据都在卷里。
        & $exe $sub @composeArgs up -d --build --force-recreate
        if ($LASTEXITCODE -ne 0) {
            Write-Host "容器启动失败。Docker Desktop 是否已经启动？" -ForegroundColor Red
            exit 1
        }

        $url = Get-PublicUrl
        Write-Host "等待平台就绪..."
        for ($i = 0; $i -lt 120; $i++) {
            if (Test-PlatformReady $url) {
                Write-Host ""
                Write-Host "统一身份认证平台已就绪：$url" -ForegroundColor Green
                Write-Host "平台管理员登录后进管理平台，业务人员登录后进应用门户。"
                Write-Host ""
                exit 0
            }
            Start-Sleep -Seconds 1
        }

        Write-Host "平台未在 120 秒内就绪，用 .\scripts\sso.ps1 logs 查看原因。" -ForegroundColor Red
        exit 1
    }

    'stop' {
        $composeArgs = Get-ComposeArgs
        & $exe $sub @composeArgs down
        exit $LASTEXITCODE
    }

    'status' {
        $composeArgs = Get-ComposeArgs
        & $exe $sub @composeArgs ps
        Write-Host ""
        $url = Get-PublicUrl
        if (Test-PlatformReady $url) {
            Write-Host "对外入口 $url 可访问。" -ForegroundColor Green
        } else {
            Write-Host "对外入口 $url 不可访问。" -ForegroundColor Yellow
        }
        exit 0
    }

    'logs' {
        $composeArgs = Get-ComposeArgs
        if ($Rest) { & $exe $sub @composeArgs logs -f @Rest } else { & $exe $sub @composeArgs logs -f }
        exit $LASTEXITCODE
    }

    default {
        Write-Host "用法：.\scripts\sso.ps1 {start|stop|status|logs [服务名]}"
        exit 1
    }
}
