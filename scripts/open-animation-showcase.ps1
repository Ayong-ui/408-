$ErrorActionPreference = 'Stop'
$url = 'http://127.0.0.1:7788/animation-showcase.html'
$ready = $false
try { $ready = (Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2).StatusCode -eq 200 } catch {}
if (-not $ready) {
  Write-Host '服务未运行，请先执行 .\start.bat，或在项目目录启动 Spring Boot 服务。' -ForegroundColor Yellow
  exit 1
}
Start-Process $url
Write-Host "已打开动画演示：$url"
