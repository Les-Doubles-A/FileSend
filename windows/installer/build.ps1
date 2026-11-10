# Compile l'application en release puis construit l'installeur dans installer\Output.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    cargo build --release -p filesend-app
    if ($LASTEXITCODE -ne 0) { throw "cargo build a echoue" }
    $iscc = @(
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $iscc) { throw "Inno Setup 6 introuvable" }
    $ver = (Get-Content "app\tauri.conf.json" -Raw | ConvertFrom-Json).version
    & $iscc "/DAppVersion=$ver" "installer\filesend.iss"
    if ($LASTEXITCODE -ne 0) { throw "ISCC a echoue" }
} finally { Pop-Location }
