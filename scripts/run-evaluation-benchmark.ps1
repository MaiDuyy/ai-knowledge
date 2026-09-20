# Run SecWiki evaluation golden-dataset benchmark (Java) and optionally official Ragas.
# Usage (from source/ai-knowledge):
#   .\scripts\run-evaluation-benchmark.ps1
#   .\scripts\run-evaluation-benchmark.ps1 -RunRagas

param(
    [switch]$RunRagas,
    [switch]$SkipMaven,
    [int]$MaxDocs = 15
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "=== SecWiki Evaluation Benchmark ===" -ForegroundColor Cyan

if (-not $SkipMaven) {
    if (-not $env:API_KEY -and -not $env:GEMINI_API_KEY) {
        Write-Host "Note: API_KEY not set in env — test may still read application-test.properties" -ForegroundColor Yellow
    }

    # MaxDocs=15 default (faster + friendlier free-tier). Full golden: -MaxDocs 0
    Write-Host "Max docs: $(if ($MaxDocs -le 0) { 'unlimited (full golden)' } else { $MaxDocs })" -ForegroundColor Yellow
    Write-Host "Running: mvn test -Pevaluation-benchmark -Dbenchmark.eval.maxDocs=$MaxDocs"
    & mvn test "-Pevaluation-benchmark" "-Dbenchmark.eval.maxDocs=$MaxDocs"
    if ($LASTEXITCODE -ne 0) {
        # try wrapper if mvn missing
        if (Test-Path (Join-Path $Root "mvnw.cmd")) {
            Write-Host "Retry with mvnw.cmd..."
            & (Join-Path $Root "mvnw.cmd") test "-Pevaluation-benchmark" "-Dbenchmark.eval.maxDocs=$MaxDocs"
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Maven evaluation benchmark failed with exit code $LASTEXITCODE"
        }
    }
}

$LatestExport = Join-Path $Root "benchmark\evaluation\latest\export"
if (-not (Test-Path $LatestExport)) {
    Write-Warning "No export at $LatestExport — open the run dir under benchmark/evaluation/"
} else {
    Write-Host "Export ready: $LatestExport" -ForegroundColor Green
    Get-ChildItem $LatestExport | ForEach-Object { Write-Host "  - $($_.Name)" }
}

if ($RunRagas) {
    & (Join-Path $PSScriptRoot "run-ragas-eval.ps1") -LoadTestProperties
} else {
    Write-Host ""
    Write-Host "To run official Ragas (Gemini):" -ForegroundColor Yellow
    Write-Host '  $env:API_KEY = "..."   # or GEMINI_API_KEY / GOOGLE_API_KEY'
    Write-Host "  .\scripts\run-ragas-eval.ps1 -LoadTestProperties"
}

Write-Host "Done." -ForegroundColor Green
