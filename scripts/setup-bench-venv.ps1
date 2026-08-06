# Create isolated Python env for SecWiki hybrid Ragas evaluation.
# Usage (from source/ai-knowledge):
#   .\scripts\setup-bench-venv.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$VenvDir = Join-Path $Root ".venv-bench"
$Requirements = Join-Path $Root "benchmark-eval\requirements.txt"

Write-Host "=== SecWiki-Bench: setup .venv-bench ===" -ForegroundColor Cyan

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command py -ErrorAction SilentlyContinue
    if (-not $python) {
        throw "Python not found on PATH. Install Python 3.10+ and retry."
    }
    $pyExe = "py"
    $pyArgs = @("-3", "-m", "venv", $VenvDir)
} else {
    $pyExe = "python"
    $pyArgs = @("-m", "venv", $VenvDir)
}

if (-not (Test-Path $Requirements)) {
    throw "Missing $Requirements"
}

if (-not (Test-Path $VenvDir)) {
    Write-Host "Creating venv at $VenvDir"
    & $pyExe @pyArgs
} else {
    Write-Host "Venv already exists: $VenvDir"
}

$pip = Join-Path $VenvDir "Scripts\pip.exe"
$pythonVenv = Join-Path $VenvDir "Scripts\python.exe"
if (-not (Test-Path $pip)) {
    throw "pip not found in venv: $pip"
}

Write-Host "Upgrading pip..."
& $pythonVenv -m pip install --upgrade pip
Write-Host "Installing requirements..."
& $pip install -r $Requirements

Write-Host "Verifying ragas import..."
& $pythonVenv -c "import ragas; print('ragas', ragas.__version__)"

Write-Host ""
Write-Host "Done. Activate with:" -ForegroundColor Green
Write-Host "  .\.venv-bench\Scripts\Activate.ps1"
Write-Host "Or run:"
Write-Host "  .\scripts\run-ragas-eval.ps1"
