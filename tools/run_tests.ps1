# Build + run the JUnit 5 test suite without requiring Maven.
# On first run, downloads junit-platform-console-standalone to lib/.
# Usage: powershell -ExecutionPolicy Bypass -File tools/run_tests.ps1

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$JUnitVersion = "1.10.2"
$JUnitJarName = "junit-platform-console-standalone-$JUnitVersion.jar"
$JUnitJar = Join-Path $RepoRoot "lib\$JUnitJarName"
$EcjJar = Join-Path $RepoRoot "ecj.23.jar"
$MainSrcDir = Join-Path $RepoRoot "src\main\java"
$TestSrcDir = Join-Path $RepoRoot "src\test\java"
$MainBuildDir = Join-Path $RepoRoot "build\test-main-classes"
$TestBuildDir = Join-Path $RepoRoot "build\test-classes"
$LibDir = Join-Path $RepoRoot "lib"

if (-not (Test-Path $JUnitJar)) {
    New-Item -ItemType Directory -Force -Path $LibDir | Out-Null
    $url = "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUnitVersion/$JUnitJarName"
    Write-Host "Downloading $JUnitJarName ..."
    Invoke-WebRequest -Uri $url -OutFile $JUnitJar
}

Remove-Item -Recurse -Force $MainBuildDir, $TestBuildDir -ErrorAction Ignore
New-Item -ItemType Directory -Force -Path $MainBuildDir, $TestBuildDir | Out-Null

$mainSources = Get-ChildItem -Recurse -Filter *.java -Path $MainSrcDir | ForEach-Object { $_.FullName }
Write-Host "Compiling main sources ..."
& javac -cp $EcjJar -d $MainBuildDir $mainSources
if ($LASTEXITCODE -ne 0) { throw "Main compile failed" }

if (-not (Test-Path $TestSrcDir)) {
    Write-Host "No test directory found at $TestSrcDir. Nothing to run."
    exit 0
}

$testSources = Get-ChildItem -Recurse -Filter *.java -Path $TestSrcDir | ForEach-Object { $_.FullName }
if (-not $testSources) {
    Write-Host "No test sources found. Nothing to run."
    exit 0
}

$compileCp = "$MainBuildDir;$EcjJar;$JUnitJar"
Write-Host "Compiling test sources ..."
& javac -cp $compileCp -d $TestBuildDir $testSources
if ($LASTEXITCODE -ne 0) { throw "Test compile failed" }

$runCp = "$MainBuildDir;$TestBuildDir;$EcjJar"
Write-Host "Running JUnit 5 tests ..."
& java -jar $JUnitJar execute --class-path $runCp --scan-class-path
exit $LASTEXITCODE
