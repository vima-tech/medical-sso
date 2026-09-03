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
    if (-not (Test-Path '.env')) { return $null }
    foreach ($line in Get-Content '.env' -Encoding UTF8) {
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

$compose = Get-ComposeCommand
$exe = $compose[0]
$sub = $compose[1]

switch ($Command) {
    'start' {
        if (-not (Test-Path '.env')) {
            Copy-Item '.env.example' '.env'
            Write-Host "已从 .env.example 创建 .env。演示环境可直接使用，正式环境请先修改密码。" -ForegroundColor Yellow
        }

        Write-Host "启动中（首次会构建平台镜像，需要几分钟，请耐心等待）..." -ForegroundColor Cyan
        & $exe $sub up -d --build
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
        & $exe $sub down
        exit $LASTEXITCODE
    }

    'status' {
        & $exe $sub ps
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
        if ($Rest) { & $exe $sub logs -f @Rest } else { & $exe $sub logs -f }
        exit $LASTEXITCODE
    }

    default {
        Write-Host "用法：.\scripts\sso.ps1 {start|stop|status|logs [服务名]}"
        exit 1
    }
}
