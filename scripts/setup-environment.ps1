param(
    [string]$DevRoot = 'E:\AndroidDev'
)

$ErrorActionPreference = 'Stop'
$DevRoot = [System.IO.Path]::GetFullPath($DevRoot)
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Downloads = Join-Path $DevRoot 'downloads'
$SdkRoot = Join-Path $DevRoot 'android-sdk'
New-Item -ItemType Directory -Force -Path $Downloads | Out-Null

function Get-Download([string]$Url, [string]$Destination) {
    if (!(Test-Path -LiteralPath $Destination)) {
        Write-Host "Downloading $Url"
        $PartialDownload = "$Destination.part"
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            & curl.exe -L --fail --retry 3 --connect-timeout 30 --output $PartialDownload $Url
            if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
        } else {
            Invoke-WebRequest -Uri $Url -OutFile $PartialDownload -UseBasicParsing
        }
        Move-Item -LiteralPath $PartialDownload -Destination $Destination -Force
    }
}

$JavaHome = Get-ChildItem -LiteralPath (Join-Path $DevRoot 'jdk') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
    Select-Object -First 1 -ExpandProperty FullName
if (!$JavaHome) {
    $JdkZip = Join-Path $Downloads 'jdk17.zip'
    Get-Download 'https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip' $JdkZip
    Expand-Archive -LiteralPath $JdkZip -DestinationPath (Join-Path $DevRoot 'jdk') -Force
    $JavaHome = Get-ChildItem -LiteralPath (Join-Path $DevRoot 'jdk') -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$JavaHome) { throw 'JDK 17 installation was not found.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:GRADLE_USER_HOME = Join-Path $DevRoot 'gradle-cache'
$env:PATH = "$JavaHome\bin;$SdkRoot\platform-tools;$env:PATH"
$SocketRoot = Join-Path $DevRoot 'java-sockets'
New-Item -ItemType Directory -Force -Path $SocketRoot | Out-Null
# Give Java NIO sockets a regular directory when launched by a packaged Windows app.
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$SocketRoot".Trim()
$SdkNetworkOptions = @()
$ProxyAddress = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
if ($ProxyAddress) {
    $ProxyUri = [uri]$ProxyAddress
    if ($ProxyUri.Scheme -eq 'http' -and !$ProxyUri.UserInfo) {
        $SdkNetworkOptions = @('--proxy=http', "--proxy_host=$($ProxyUri.Host)", "--proxy_port=$($ProxyUri.Port)")
    }
}

$GradleRoot = Join-Path $DevRoot 'gradle-8.9'
if (!(Test-Path -LiteralPath (Join-Path $GradleRoot 'bin\gradle.bat'))) {
    $GradleZip = Join-Path $Downloads 'gradle-8.9-bin.zip'
    Get-Download 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' $GradleZip
    $ShaFile = Join-Path $Downloads 'gradle-8.9-bin.zip.sha256'
    Get-Download 'https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256' $ShaFile
    $ExpectedHash = (Get-Content -LiteralPath $ShaFile -Raw).Trim()
    if ((Get-FileHash -LiteralPath $GradleZip -Algorithm SHA256).Hash -ne $ExpectedHash) {
        throw 'Gradle archive checksum does not match the official checksum.'
    }
    Expand-Archive -LiteralPath $GradleZip -DestinationPath $DevRoot -Force
}

$ToolsRoot = Join-Path $SdkRoot 'cmdline-tools\latest'
$SdkManager = Join-Path $ToolsRoot 'bin\sdkmanager.bat'
if (!(Test-Path -LiteralPath $SdkManager)) {
    $ToolsZip = Join-Path $Downloads 'commandlinetools.zip'
    Get-Download 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $ToolsZip
    $ToolsStaging = Join-Path $Downloads 'commandlinetools-unpacked'
    Expand-Archive -LiteralPath $ToolsZip -DestinationPath $ToolsStaging -Force
    New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
    Copy-Item -Path (Join-Path $ToolsStaging 'cmdline-tools\*') -Destination $ToolsRoot -Recurse -Force
}

Write-Host 'Accepting Android SDK development licenses and installing API 35 tools...'
1..100 | ForEach-Object { 'y' } | & $SdkManager "--sdk_root=$SdkRoot" @SdkNetworkOptions --licenses |
    Out-File -LiteralPath (Join-Path $Downloads 'sdk-licenses.log') -Encoding UTF8
if ($LASTEXITCODE -ne 0) { throw 'Android SDK license acceptance failed.' }
& $SdkManager "--sdk_root=$SdkRoot" @SdkNetworkOptions 'platform-tools' 'platforms;android-35' 'build-tools;34.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed.' }

$SdkProperty = $SdkRoot.Replace('\', '\\').Replace(':', '\:')
"sdk.dir=$SdkProperty" | Set-Content -LiteralPath (Join-Path $ProjectRoot 'local.properties') -Encoding ASCII
Write-Host "Ready. JDK: $JavaHome"
Write-Host "Android SDK: $SdkRoot"
Write-Host "Gradle: $GradleRoot"
Write-Host 'Build with: powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1'
