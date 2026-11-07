param(
    [Parameter(Mandatory = $true)][string]$RuntimeJar,
    [ValidateRange(1, 100)][int]$Forks = 3,
    [ValidateSet('default', 'raw')][string]$View = 'default'
)
# Host allocation probe for the MeguvizAndroid bridge (MeguvizBinding).
# Requires the meguviz-android-runtime artifact (1.2.0 or compatible) as a jar on the classpath.
# Example:
#   powershell -ExecutionPolicy Bypass -File benchmark/run-binding-probe.ps1 `
#       -RuntimeJar "$env:USERPROFILE\.gradle\caches\9.8.2\transforms\<hash>\transformed\meguviz-android-runtime-1.2.0-runtime.jar"
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not (Test-Path -LiteralPath $RuntimeJar)) { throw "RuntimeJar not found: $RuntimeJar" }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$run = "binding-allocation-$View-$stamp"
$classes = Join-Path $root "benchmark/build/$run/classes"
$logs = Join-Path $root "docs/engineering/evidence/runs/$run"
New-Item -ItemType Directory -Path $classes,$logs | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'core/src/main/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$sources += (Join-Path $root 'meguviz-android/src/main/java/io/github/jichuo1/meguviz/drawable/meguviz/android/MeguvizBinding.java')
$sources += (Join-Path $root 'benchmark/BindingPostAllocationProbe.java')
$sources = @($sources | Sort-Object)
$before = @(Get-FileHash -Algorithm SHA256 -LiteralPath $sources | ForEach-Object { "$($_.Hash) $($_.Path.Substring($root.Length + 1))" })
$before | Set-Content -LiteralPath (Join-Path $logs 'source-sha256.txt') -Encoding utf8
$head = & git -C $root rev-parse HEAD
@("commit=$head", "view=$View", "runtime_jar=$RuntimeJar", 'compile=javac --release 17 -encoding UTF-8',
  "forks=$Forks", "jvm=$($jvmArgs -join ' ')") | Set-Content -LiteralPath (Join-Path $logs 'protocol.txt') -Encoding utf8
& javac --release 17 -encoding UTF-8 -cp $RuntimeJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
$after = @(Get-FileHash -Algorithm SHA256 -LiteralPath $sources | ForEach-Object { "$($_.Hash) $($_.Path.Substring($root.Length + 1))" })
if (Compare-Object $before $after) { throw 'Source changed while compiling; this run is not valid.' }
$jvmArgs = @()
if ($View -eq 'raw') { $jvmArgs += '-XX:-DoEscapeAnalysis'; $jvmArgs += '-Dprobe.rawAllocation=true' }
for ($fork = 0; $fork -lt $Forks; $fork++) {
    $log = Join-Path $logs "fork-$fork.csv"
    & java $jvmArgs -cp "$classes;$RuntimeJar" io.github.jichuo1.meguviz.drawable.benchmark.BindingPostAllocationProbe > $log
    if ($LASTEXITCODE -ne 0) { throw "fork $fork failed with exit code $LASTEXITCODE; see $log" }
    Write-Host "fork $fork finished: $log"
}
Write-Host "Results: $logs"
