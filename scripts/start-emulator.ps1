param(
    [string]$DevRoot = 'E:\AndroidDev',
    [string]$Name = 'ShikeTest',
    [int]$Port = 5556,
    [switch]$Visible
)

$ErrorActionPreference = 'Stop'
$DevRoot = [System.IO.Path]::GetFullPath($DevRoot)
$SdkRoot = Join-Path $DevRoot 'android-sdk'
$JavaHome = Get-ChildItem -LiteralPath (Join-Path $DevRoot 'jdk') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
    Select-Object -First 1 -ExpandProperty FullName
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_AVD_HOME = Join-Path $DevRoot 'avd'
$env:ANDROID_USER_HOME = Join-Path $DevRoot 'android-user-home'
$SocketRoot = Join-Path $DevRoot 'java-sockets'
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME, $env:ANDROID_USER_HOME, $SocketRoot | Out-Null
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$SocketRoot".Trim()

$SdkNetworkOptions = @()
$ProxyAddress = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
if ($ProxyAddress) {
    $ProxyUri = [uri]$ProxyAddress
    if ($ProxyUri.Scheme -eq 'http' -and !$ProxyUri.UserInfo) {
        $SdkNetworkOptions = @('--proxy=http', "--proxy_host=$($ProxyUri.Host)", "--proxy_port=$($ProxyUri.Port)")
    }
}
$Emulator = Join-Path $SdkRoot 'emulator\emulator.exe'
$SystemImage = 'system-images;android-30;default;x86_64'
if (!(Test-Path -LiteralPath $Emulator) -or
    !(Test-Path -LiteralPath (Join-Path $SdkRoot 'system-images\android-30\default\x86_64\system.img'))) {
    & (Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat') "--sdk_root=$SdkRoot" @SdkNetworkOptions 'emulator' $SystemImage
    if ($LASTEXITCODE -ne 0) { throw 'Emulator tools installation failed.' }
}

$AvdPath = Join-Path $env:ANDROID_AVD_HOME "$Name.avd"
if (!(Test-Path -LiteralPath (Join-Path $AvdPath 'config.ini'))) {
    'no' | & (Join-Path $SdkRoot 'cmdline-tools\latest\bin\avdmanager.bat') create avd --name $Name --package $SystemImage --device 'pixel_4' --path $AvdPath
    if ($LASTEXITCODE -ne 0) { throw 'Test AVD creation failed.' }
}

$Serial = "emulator-$Port"
$Adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
$Devices = (& $Adb devices) -join "`n"
if ($Devices -match [regex]::Escape($Serial)) {
    Write-Host "$Serial is already running."
    exit 0
}

$EmulatorArguments = @('-avd', $Name, '-port', "$Port", '-timezone', 'Asia/Shanghai', '-no-audio', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-memory', '2048', '-camera-front', 'none', '-camera-back', 'none')
if (!$Visible) { $EmulatorArguments += '-no-window' }
$Acceleration = (& $Emulator -accel-check 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { $EmulatorArguments += @('-accel', 'off') }
$Logs = Join-Path $DevRoot 'emulator-logs'
New-Item -ItemType Directory -Force -Path $Logs | Out-Null
$Process = Start-Process -FilePath $Emulator -ArgumentList $EmulatorArguments -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $Logs "$Name.stdout.log") `
    -RedirectStandardError (Join-Path $Logs "$Name.stderr.log") -PassThru
Write-Host "Started $Name (PID $($Process.Id)); test device: $Serial"
Write-Host "Install APK: $Adb -s $Serial install -r app\build\outputs\apk\debug\app-debug.apk"
