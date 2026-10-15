param(
    [string]$DevRoot = 'E:\AndroidDev',
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest')
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DevRoot = [System.IO.Path]::GetFullPath($DevRoot)
$BuildProjectRoot = $ProjectRoot
if ($ProjectRoot -match '[^\x00-\x7F]') {
    # JDK 17 on Windows decodes @argfile classpaths with the native code page.
    # A junction keeps all generated files in the workspace while giving Gradle
    # and its test workers an ASCII path to the same source directory.
    $ProjectsRoot = Join-Path $DevRoot 'projects'
    $BuildProjectRoot = Join-Path $ProjectsRoot 'ShikeTimetable'
    New-Item -ItemType Directory -Force -Path $ProjectsRoot | Out-Null
    if (!(Test-Path -LiteralPath $BuildProjectRoot)) {
        New-Item -ItemType Junction -Path $BuildProjectRoot -Target $ProjectRoot | Out-Null
    } else {
        $BuildLink = Get-Item -LiteralPath $BuildProjectRoot
        if ($BuildLink.LinkType -ne 'Junction' -or
            [System.IO.Path]::GetFullPath([string]$BuildLink.Target) -ne [System.IO.Path]::GetFullPath($ProjectRoot)) {
            throw "The build junction $BuildProjectRoot already points to another project. Pass a different -DevRoot."
        }
    }
}
$SdkRoot = Join-Path $DevRoot 'android-sdk'
$JavaHome = Get-ChildItem -LiteralPath (Join-Path $DevRoot 'jdk') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
    Select-Object -First 1 -ExpandProperty FullName
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms\android-35\android.jar'))) {
    throw 'Run .\scripts\setup-environment.ps1 first, or pass -DevRoot pointing to the installed tools.'
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:GRADLE_USER_HOME = Join-Path $DevRoot 'gradle-cache'
$env:PATH = "$JavaHome\bin;$SdkRoot\platform-tools;$env:PATH"
$SocketRoot = Join-Path $DevRoot 'java-sockets'
New-Item -ItemType Directory -Force -Path $SocketRoot | Out-Null
# Avoid the redirected TEMP folder when Java creates its local selector socket.
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$SocketRoot".Trim()
$GradleNetworkOptions = @()
$ProxyAddress = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
if ($ProxyAddress) {
    $ProxyUri = [uri]$ProxyAddress
    if ($ProxyUri.Scheme -eq 'http' -and !$ProxyUri.UserInfo) {
        $GradleNetworkOptions = @(
            "-Dhttp.proxyHost=$($ProxyUri.Host)", "-Dhttp.proxyPort=$($ProxyUri.Port)",
            "-Dhttps.proxyHost=$($ProxyUri.Host)", "-Dhttps.proxyPort=$($ProxyUri.Port)",
            '-Dhttp.nonProxyHosts=localhost'
        )
    }
}
$SdkProperty = $SdkRoot.Replace('\', '\\').Replace(':', '\:')
"sdk.dir=$SdkProperty" | Set-Content -LiteralPath (Join-Path $ProjectRoot 'local.properties') -Encoding ASCII

$GradleExecutable = Join-Path $DevRoot 'gradle-8.9\bin\gradle.bat'
if (!(Test-Path -LiteralPath $GradleExecutable)) {
    $GradleExecutable = Join-Path $ProjectRoot 'gradlew.bat'
}
Push-Location $BuildProjectRoot
try {
    $BuildTasks = @($Tasks | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    & $GradleExecutable @BuildTasks @GradleNetworkOptions '--console=plain' '--stacktrace'
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
