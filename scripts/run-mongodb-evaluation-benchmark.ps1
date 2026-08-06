# Run SecWiki Golden Dataset evaluation with MongoDB storage track (Testcontainers).
# Usage (from source/ai-knowledge):
#   .\scripts\run-mongodb-evaluation-benchmark.ps1
#   .\scripts\run-mongodb-evaluation-benchmark.ps1 -MaxDocs 5

param(
    [int]$MaxDocs = 5
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "=== SecWiki MongoDB Golden Evaluation ===" -ForegroundColor Cyan
Write-Host "Requires: Docker + API_KEY (or application-test.properties)" -ForegroundColor Yellow
if ($MaxDocs -le 0) {
    Write-Host "Max docs: unlimited" -ForegroundColor Yellow
} else {
    Write-Host "Max docs: $MaxDocs" -ForegroundColor Yellow
}

$mvnArgs = @(
    "test",
    "-Pevaluation-mongodb-benchmark",
    "-Dbenchmark.eval.maxDocs=$MaxDocs"
)

if (Test-Path (Join-Path $Root "mvnw.cmd")) {
    & (Join-Path $Root "mvnw.cmd") @mvnArgs
} else {
    & mvn @mvnArgs
}

if ($LASTEXITCODE -ne 0) {
    throw "Mongo evaluation benchmark failed with exit code $LASTEXITCODE"
}

$Latest = Join-Path $Root "benchmark\evaluation-mongodb\latest"
if (Test-Path $Latest) {
    Write-Host "Latest report: $Latest" -ForegroundColor Green
    Get-ChildItem $Latest -Recurse -File | Select-Object -First 20 | ForEach-Object {
        $rel = $_.FullName.Substring($Root.Length + 1)
        Write-Host "  - $rel"
    }
} else {
    Write-Warning "No report under benchmark/evaluation-mongodb/latest - check surefire logs"
}

Write-Host "Done." -ForegroundColor Green
