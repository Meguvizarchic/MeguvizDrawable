param(
    [ValidateRange(1, 100)][int]$Forks = 3,
    [ValidateSet('default', 'raw')][string]$View = 'default'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$run = "flightcache-allocation-$View-$stamp"
$classes = Join-Path $root "benchmark/build/$run/classes"
$logs = Join-Path $root "docs/engineering/evidence/runs/$run"
New-Item -ItemType Directory -Path $classes,$logs | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'core/src/main/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$sources += Join-Path $root 'benchmark/FlightCacheAllocationProbe.java'
$sources = @($sources | Sort-Object)
$before = @(Get-FileHash -Algorithm SHA256 -LiteralPath $sources | ForEach-Object { "$($_.Hash) $($_.Path.Substring($root.Length + 1))" })
$before | Set-Content -LiteralPath (Join-Path $logs 'source-sha256.txt') -Encoding utf8
$head = & git -C $root rev-parse HEAD
# The 'raw' view adds -XX:-DoEscapeAnalysis so allocations the JIT would otherwise remove are counted;
# the probe records the same flag through -Dprobe.rawAllocation. Neither view is an Android/ART measurement.
$jvmArgs = @()
if ($View -eq 'raw') { $jvmArgs += '-XX:-DoEscapeAnalysis'; $jvmArgs += '-Dprobe.rawAllocation=true' }
@("commit=$head", "view=$View", 'compile=javac --release 8 -encoding UTF-8', "forks=$Forks", 'warmup_rounds=4', 'measured_rounds=7', 'operations_per_round=1000000', "jvm=$($jvmArgs -join ' ')") | Set-Content -LiteralPath (Join-Path $logs 'protocol.txt') -Encoding utf8
& javac --release 8 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
$after = @(Get-FileHash -Algorithm SHA256 -LiteralPath $sources | ForEach-Object { "$($_.Hash) $($_.Path.Substring($root.Length + 1))" })
if (Compare-Object $before $after) { throw 'Source changed while compiling; this run is not valid.' }
for ($fork = 0; $fork -lt $Forks; $fork++) {
    $log = Join-Path $logs "fork-$fork.csv"
    & java $jvmArgs -cp $classes io.github.jichuo1.meguviz.drawable.benchmark.FlightCacheAllocationProbe "$fork" > $log
    if ($LASTEXITCODE -ne 0) { throw "fork $fork failed with exit code $LASTEXITCODE; see $log" }
    Write-Host "fork $fork finished: $log"
}
Write-Host "Results: $logs"
