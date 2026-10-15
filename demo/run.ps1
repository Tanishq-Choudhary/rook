param(
    [string]$Fixture = "method-rename-pay-local",
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if ($Out -eq "") { $Out = Join-Path $root "target\demo" }

Push-Location $root
try {
    mvn -q install "-Dmaven.test.skip=true" "-Dspotless.check.skip=true" "-Djacoco.skip=true" -pl rook-eval -am
    $cpFile = Join-Path $root "target\demo-classpath.txt"
    mvn -q -pl rook-eval dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    $classpath = (Join-Path $root "rook-eval\target\classes") + ";" + (Get-Content $cpFile -Raw).Trim()

    if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
    java -cp $classpath dev.rook.eval.EvalCli run (Join-Path $root "eval\fixtures") $Out $Fixture

    Write-Host ""
    Write-Host "before and after for $Fixture"
    $before = Join-Path $root "eval\fixtures\$Fixture\repo\src\main"
    $after = Join-Path $root "eval\fixtures\$Fixture\gold\src\main"
    git diff --no-index --stat $before $after
    git diff --no-index $before $after
}
finally {
    Pop-Location
}
