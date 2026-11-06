# Runs the benchmark, unattended, on each world preset in turn: a fresh world each time, in a fresh game. Each takes a few minutes.
# Leave the game window alone while it runs. Results go in run\benchmarks\.
#
#   powershell -ExecutionPolicy Bypass -File bench.ps1
#   powershell -ExecutionPolicy Bypass -File bench.ps1 -Presets worlddepthoptimisation:flat_300 -Repeats 3
param(
    [string[]]$Presets = @('worlddepthoptimisation:flat_300', 'worlddepthoptimisation:flat_1000', 'worlddepthoptimisation:ocean_1936'),
    [int]$Repeats = 1,
    [string]$Suffix = '',
    [switch]$FloorOff,
    [switch]$CheckRoute,
    [int]$MaxDepth = 0,
    [switch]$VanillaHeight
)

$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.5.0.3'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force 'run\benchmarks' | Out-Null
$memoryFile = 'run\benchmarks\process-memory.csv'
if (-not (Test-Path $memoryFile)) { 'label,peak_working_set_mb' | Out-File $memoryFile -Encoding ascii }

for ($i = 1; $i -le $Repeats; $i++) {
    foreach ($preset in $Presets) {
        $label = ($preset -replace '.*:', '') + $Suffix
        $options = "-Dworlddepthoptimisation.autobench=$preset -Dworlddepthoptimisation.label=$label"
        if ($FloorOff) { $options += ' -Dworlddepthoptimisation.enabled=false' }
        if ($CheckRoute) { $options += ' -Dworlddepthoptimisation.checkroute=true' }
        if ($MaxDepth -gt 0) { $options += " -Dworlddepthoptimisation.maxdepth=$MaxDepth" }
        $env:JAVA_TOOL_OPTIONS = $options
        Write-Output "=== $label (run $i of $Repeats)"
        $gradleArgs = @('runClient', '--console=plain')
        if ($VanillaHeight) { $gradleArgs += '-PvanillaHeight=true' }
        $gradle = Start-Process -FilePath '.\gradlew.bat' -ArgumentList $gradleArgs -NoNewWindow -PassThru `
            -RedirectStandardOutput "run\benchmarks\last-$label.out" -RedirectStandardError "run\benchmarks\last-$label.err"
        $peak = 0
        while (-not $gradle.WaitForExit(3000)) {
            # The game is the Java process launched for this project; the Gradle daemon is not.
            $game = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'KnotClient|devlaunchinjector' -and $_.CommandLine -match 'worlddepthoptimisation' }
            foreach ($g in $game) {
                $p = Get-Process -Id $g.ProcessId -ErrorAction SilentlyContinue
                if ($p -and $p.WorkingSet64 -gt $peak) { $peak = $p.WorkingSet64 }
            }
        }
        "$label,$([int]($peak / 1MB))" | Out-File $memoryFile -Encoding ascii -Append
        Write-Output "    game exited, peak working set $([int]($peak / 1MB)) MB"
    }
}
$env:JAVA_TOOL_OPTIONS = $null
