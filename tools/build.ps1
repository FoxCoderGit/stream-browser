param([switch]$NativeTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$hash = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($projectRoot))).Replace('-', '').Substring(0, 12)
$stage = Join-Path $env:LOCALAPPDATA "StreamBrowserBuild\$hash"
New-Item -ItemType Directory -Force -Path $stage | Out-Null
# An ASCII staging path avoids Gradle test-worker classpath failures on Unicode paths.
& robocopy $projectRoot $stage /E /XD .gradle .cxx build artifacts .git /XF upstream.aar /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -gt 7) { throw 'Source staging failed' }
$nativeJdk = $env:JAVA_HOME
$studioJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path "$studioJdk\bin\java.exe") { $env:JAVA_HOME = $studioJdk }
$env:DEBUG = ''
Push-Location $stage
try {
    $arguments = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', '--console=plain')
    if ($NativeTests) {
        $buildJdk = $env:JAVA_HOME
        $env:JAVA_HOME = $nativeJdk
        & .\tools\test-native.cmd
        if ($LASTEXITCODE -ne 0) { throw 'Native host compilation failed; JAVA_HOME must name a JDK with JNI headers' }
        $env:JAVA_HOME = $buildJdk
        $arguments += "-PnativeTestLibraryDirectory=$stage\build\native-host"
    }
    & .\gradlew.bat @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build failed. Inspect $stage\app\build\reports" }
    $artifacts = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
    Copy-Item -LiteralPath "$stage\app\build\outputs\apk\debug\app-debug.apk" -Destination "$artifacts\StreamBrowser-debug.apk" -Force
    Copy-Item -LiteralPath "$stage\app\build\reports" -Destination $artifacts -Recurse -Force
    Write-Output "APK: $artifacts\StreamBrowser-debug.apk"
} finally { Pop-Location; $env:JAVA_HOME = $nativeJdk }
exit 0
