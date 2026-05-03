param(
    [string]$Dir = "app\release"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Dir)) {
    Write-Error "Directory not found: $Dir"
    exit 1
}

$apk = Get-ChildItem -Path $Dir -Filter "*.apk" | Where-Object { $_.Name -notmatch '^\d{8}\.apk$' } | Select-Object -First 1
if (-not $apk) {
    Write-Error "No APK found in: $Dir"
    exit 1
}

$dateSuffix = (Get-Date).ToString("yyyyMMdd")
$targetName = "PixelCarrierSettings-$dateSuffix.apk"
$targetPath = Join-Path $Dir $targetName

Move-Item -Path $apk.FullName -Destination $targetPath -Force
Write-Host "Renamed: $($apk.Name) -> $targetName"
