# Run official Ragas + hybrid merge on the latest evaluation export.
# Default provider: Gemini (API_KEY / GEMINI_API_KEY / GOOGLE_API_KEY)
# Usage (from source/ai-knowledge):
#   $env:API_KEY = "..."
#   .\scripts\run-ragas-eval.ps1
#   .\scripts\run-ragas-eval.ps1 -LoadTestProperties
#   .\scripts\run-ragas-eval.ps1 -Limit 6

param(
    [string]$ExportDir = "benchmark\evaluation\latest\export",
    [string]$OutDir = "",
    [string]$Provider = "gemini",
    [int]$Limit = 0,
    [switch]$SkipMerge,
    [switch]$LoadTestProperties
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Get-TestApiKey {
    $props = Join-Path $Root "src\test\resources\application-test.properties"
    if (-not (Test-Path $props)) { return $null }
    foreach ($line in Get-Content $props) {
        if ($line -match '^\s*API_KEY\s*=\s*(.+)\s*$') {
            $val = $Matches[1].Trim()
            if ($val -and -not $val.StartsWith("dummy")) { return $val }
        }
    }
    return $null
}

if (-not $env:RAGAS_PROVIDER) {
    $env:RAGAS_PROVIDER = $Provider
}

$hasGemini = $env:API_KEY -or $env:GEMINI_API_KEY -or $env:GOOGLE_API_KEY
$hasOpenAI = $env:OPENAI_API_KEY

if (-not $hasGemini -and ($LoadTestProperties -or $env:RAGAS_PROVIDER -eq "gemini")) {
    $fromProps = Get-TestApiKey
    if ($fromProps) {
        $env:API_KEY = $fromProps
        $env:GOOGLE_API_KEY = $fromProps
        Write-Host "Loaded Gemini API_KEY from application-test.properties" -ForegroundColor Yellow
        $hasGemini = $true
    }
}

if ($env:RAGAS_PROVIDER -eq "gemini" -and -not $hasGemini) {
    throw "Gemini selected but no API key. Set API_KEY / GEMINI_API_KEY / GOOGLE_API_KEY, or use -LoadTestProperties"
}
if ($env:RAGAS_PROVIDER -eq "openai" -and -not $hasOpenAI) {
    throw "OpenAI selected but OPENAI_API_KEY is missing"
}

if ($env:API_KEY -and -not $env:GOOGLE_API_KEY) { $env:GOOGLE_API_KEY = $env:API_KEY }
if ($env:GEMINI_API_KEY -and -not $env:GOOGLE_API_KEY) { $env:GOOGLE_API_KEY = $env:GEMINI_API_KEY }

if (-not $env:RAGAS_LLM_MODEL -and $env:RAGAS_PROVIDER -eq "gemini") {
    $env:RAGAS_LLM_MODEL = "gemini-3.1-flash-lite"
}
if (-not $env:RAGAS_EMBEDDING_MODEL -and $env:RAGAS_PROVIDER -eq "gemini") {
    $env:RAGAS_EMBEDDING_MODEL = "models/gemini-embedding-001"
}

$Python = Join-Path $Root ".venv-bench\Scripts\python.exe"
if (-not (Test-Path $Python)) {
    throw "Missing .venv-bench. Run .\scripts\setup-bench-venv.ps1 first."
}

$ExportPath = Join-Path $Root $ExportDir
if (-not (Test-Path $ExportPath)) {
    throw "Export dir not found: $ExportPath. Run: mvn test -Pevaluation-benchmark"
}

foreach ($f in @("ragas_claims.jsonl", "ragas_structured.jsonl", "export-manifest.json")) {
    if (-not (Test-Path (Join-Path $ExportPath $f))) {
        Write-Warning "Missing export file: $f"
    }
}
$publishFile = Join-Path $ExportPath "ragas_publish.jsonl"
if (-not (Test-Path $publishFile)) {
    Write-Warning "Missing ragas_publish.jsonl — post-Publish track will be skipped."
    Write-Host "  Rebuild: re-run Java evaluation, or:" -ForegroundColor Yellow
    Write-Host "  .\.venv-bench\Scripts\python.exe benchmark-eval\build_publish_export.py --synthetic-from-map --force" -ForegroundColor Yellow
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path (Split-Path $ExportPath -Parent) "ragas"
}

Write-Host "=== Official Ragas evaluation ===" -ForegroundColor Cyan
Write-Host "Provider: $($env:RAGAS_PROVIDER)"
Write-Host "LLM:      $($env:RAGAS_LLM_MODEL)"
Write-Host "Export:   $ExportPath"
Write-Host "Out:      $OutDir"

$claimN = 0
$structN = 0
$publishN = 0
$claimsFile = Join-Path $ExportPath "ragas_claims.jsonl"
$structFile = Join-Path $ExportPath "ragas_structured.jsonl"
if (Test-Path $claimsFile) { $claimN = (Get-Content $claimsFile | Measure-Object -Line).Lines }
if (Test-Path $structFile) { $structN = (Get-Content $structFile | Measure-Object -Line).Lines }
if (Test-Path $publishFile) { $publishN = (Get-Content $publishFile | Measure-Object -Line).Lines }
Write-Host "Export size: $claimN claims, $structN structured, $publishN publish pages" -ForegroundColor Yellow
if ($claimN -lt 20 -or $structN -lt 20) {
    Write-Host "NOTE: This is NOT a full golden export (~40 docs). Re-run Java eval first:" -ForegroundColor Yellow
    Write-Host "  .\mvnw.cmd test -Pevaluation-benchmark"
}
if ($publishN -eq 0) {
    Write-Host "NOTE: No post-Publish samples. Publish Ragas metrics will be N/A." -ForegroundColor Yellow
}

$evalArgs = @(
    (Join-Path $Root "benchmark-eval\evaluate_with_ragas.py"),
    "--export-dir", $ExportPath,
    "--out-dir", $OutDir,
    "--max-workers", "1",
    "--max-context-chars", "6000",
    "--timeout", "120"
)
if ($Limit -gt 0) {
    $evalArgs += @("--limit", "$Limit")
    Write-Host "Sample limit: $Limit per metric track" -ForegroundColor Yellow
}

& $Python @evalArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Ragas evaluation failed with exit code $LASTEXITCODE" -ForegroundColor Red
    Write-Host "If TimeoutError on every job: Gemini free-tier quota exhausted (429)." -ForegroundColor Yellow
    Write-Host "  Wait for quota reset, or set RAGAS_LLM_MODEL to another flash-lite model." -ForegroundColor Yellow
    Write-Host "  Smoke test: .\scripts\run-ragas-eval.ps1 -LoadTestProperties -Limit 3" -ForegroundColor Yellow
    Write-Host "If export is small: re-run Java with .\mvnw.cmd test -Pevaluation-benchmark" -ForegroundColor Yellow
    throw "Ragas evaluation failed with exit code $LASTEXITCODE"
}

if (-not $SkipMerge) {
    $JavaReport = Join-Path (Split-Path $ExportPath -Parent) "evaluation-benchmark-report.json"
    if (-not (Test-Path $JavaReport)) {
        $JavaReport = Join-Path $Root "benchmark\evaluation\latest\evaluation-benchmark-report.json"
    }
    $MergeOut = Split-Path $ExportPath -Parent
    Write-Host "=== Hybrid merge (MAP + Publish Ragas + offline) ===" -ForegroundColor Cyan
    $env:PYTHONPATH = (Join-Path $Root "benchmark-eval")
    & $Python (Join-Path $Root "benchmark-eval\merge_hybrid_report.py") `
        --java-report $JavaReport `
        --ragas-metrics (Join-Path $OutDir "ragas-metrics.json") `
        --export-dir $ExportPath `
        --out-dir $MergeOut
    Write-Host "Hybrid report: $(Join-Path $MergeOut 'hybrid-evaluation-report.md')" -ForegroundColor Green
    Write-Host "Gates: claim faith + publish faith + extraction F1 + link F1 + forbidden hallu." -ForegroundColor Yellow
    Write-Host "Diagnostic: answer_correctness, answer_relevancy, context_recall (unless thresholds set)." -ForegroundColor Yellow
}

Write-Host "Done." -ForegroundColor Green
