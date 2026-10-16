param(
    [string]$DevRoot = 'E:\AndroidDev',
    [string]$Serial = 'emulator-5558',
    [switch]$LargeFont,
    [switch]$Widget,
    [switch]$Sagiri,
    [switch]$Sylvia
)

$ErrorActionPreference = 'Stop'
$PreviewProjectRoot = Split-Path -Parent $PSScriptRoot
$PreviewDirectory = Join-Path $PreviewProjectRoot 'screenshots'
New-Item -ItemType Directory -Force -Path $PreviewDirectory | Out-Null
$PreviewNames = if ($Sylvia -and $Widget) { @('小组件-希尔维娅-早晨', '小组件-希尔维娅-午间', '小组件-希尔维娅-夜晚') }
elseif ($Sylvia) { @('希尔维娅-课表-浅色', '希尔维娅-课表-深色', '希尔维娅-课程', '希尔维娅-画册') }
elseif ($Sagiri -and $Widget) { @('小组件-纱雾-早晨', '小组件-纱雾-午间', '小组件-纱雾-夜晚') }
elseif ($Sagiri) { @('纱雾-课表-浅色', '纱雾-课表-深色', '纱雾-课程', '纱雾-画册') }
elseif ($Widget) { @('小组件-紧凑', '小组件-展开', '小组件-午间', '小组件-夜晚', '小组件-无课', '小组件-简约') }
elseif ($LargeFont) { @('阳茉莉-课表-浅色', '阳茉莉-空课表') } else {
    @('阳茉莉-课表-浅色', '阳茉莉-课表-深色', '阳茉莉-课表-七天', '阳茉莉-课程',
      '阳茉莉-课程编辑', '阳茉莉-设置', '阳茉莉-画册', '阳茉莉-教务导入', '阳茉莉-空课表', '阳茉莉-关闭陪伴',
      '阳茉莉-点击换句', '阳茉莉-画册-新增', '主题-简约', '主题-阳茉莉-提示已隐藏')
}

# 仅读取外观或小组件测试使用虚构示例生成的截图，不捕获当前个人课表。
foreach ($PreviewName in $PreviewNames) {
    $PreviewOutputName = if (($Sagiri -or $Sylvia) -and $LargeFont) { "$PreviewName-大字号" }
    elseif (!$LargeFont) { $PreviewName } elseif ($PreviewName -eq '阳茉莉-课表-浅色') {
        '阳茉莉-大字号-课表'
    } else { '阳茉莉-大字号-空课表' }
    $PreviewStart = [System.Diagnostics.ProcessStartInfo]::new((Join-Path $DevRoot 'android-sdk/platform-tools/adb.exe'))
    $PreviewStart.UseShellExecute = $false
    $PreviewStart.RedirectStandardOutput = $true
    $PreviewStart.RedirectStandardError = $true
    $PreviewSourceName = if (($Sagiri -or $Sylvia) -and $LargeFont) { $PreviewOutputName } else { $PreviewName }
    foreach ($PreviewArgument in @('-s', $Serial, 'exec-out', 'run-as', 'com.shike.timetable', 'cat', "files/visual-check/$PreviewSourceName.png")) {
        $PreviewStart.ArgumentList.Add($PreviewArgument)
    }
    $PreviewProcess = [System.Diagnostics.Process]::Start($PreviewStart)
    $PreviewMemory = [System.IO.MemoryStream]::new()
    try {
        $PreviewProcess.StandardOutput.BaseStream.CopyTo($PreviewMemory)
        $PreviewProcess.WaitForExit()
        $PreviewBytes = $PreviewMemory.ToArray()
        if ($PreviewProcess.ExitCode -ne 0 -or $PreviewBytes.Length -lt 8 -or
            [Convert]::ToHexString($PreviewBytes[0..7]) -ne '89504E470D0A1A0A') {
            throw "Cannot read test preview $PreviewSourceName. Run the relevant appearance or widget test on $Serial first."
        }
        [System.IO.File]::WriteAllBytes((Join-Path $PreviewDirectory "$PreviewOutputName.png"), $PreviewBytes)
    } finally {
        $PreviewMemory.Dispose()
        $PreviewProcess.Dispose()
    }
}
Write-Host "Copied $($PreviewNames.Count) test previews to $PreviewDirectory."
