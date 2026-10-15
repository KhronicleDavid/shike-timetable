param(
    [string]$DevRoot = 'E:\AndroidDev',
    [string]$Serial = 'emulator-5558',
    [switch]$LargeFont,
    [switch]$Widget
)

$ErrorActionPreference = 'Stop'
$PreviewProjectRoot = Split-Path -Parent $PSScriptRoot
$PreviewDirectory = Join-Path $PreviewProjectRoot 'screenshots'
New-Item -ItemType Directory -Force -Path $PreviewDirectory | Out-Null
$PreviewNames = if ($Widget) { @('小组件-紧凑', '小组件-展开', '小组件-午间', '小组件-夜晚', '小组件-无课') }
elseif ($LargeFont) { @('阳茉莉-课表-浅色', '阳茉莉-空课表') } else {
    @('阳茉莉-课表-浅色', '阳茉莉-课表-深色', '阳茉莉-课表-七天', '阳茉莉-课程',
      '阳茉莉-课程编辑', '阳茉莉-设置', '阳茉莉-画册', '阳茉莉-教务导入', '阳茉莉-空课表', '阳茉莉-关闭陪伴',
      '阳茉莉-点击换句', '阳茉莉-画册-新增')
}

# 仅读取 HimariAppearanceTest 使用虚构示例生成的截图，不捕获当前个人课表。
foreach ($PreviewName in $PreviewNames) {
    $PreviewOutputName = if (!$LargeFont) { $PreviewName } elseif ($PreviewName -eq '阳茉莉-课表-浅色') {
        '阳茉莉-大字号-课表'
    } else { '阳茉莉-大字号-空课表' }
    $PreviewStart = [System.Diagnostics.ProcessStartInfo]::new((Join-Path $DevRoot 'android-sdk/platform-tools/adb.exe'))
    $PreviewStart.UseShellExecute = $false
    $PreviewStart.RedirectStandardOutput = $true
    $PreviewStart.RedirectStandardError = $true
    foreach ($PreviewArgument in @('-s', $Serial, 'exec-out', 'run-as', 'com.shike.timetable', 'cat', "files/visual-check/$PreviewName.png")) {
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
            throw "Cannot read test preview $PreviewName. Run HimariAppearanceTest on $Serial first."
        }
        [System.IO.File]::WriteAllBytes((Join-Path $PreviewDirectory "$PreviewOutputName.png"), $PreviewBytes)
    } finally {
        $PreviewMemory.Dispose()
        $PreviewProcess.Dispose()
    }
}
Write-Host "Copied $($PreviewNames.Count) test previews to $PreviewDirectory."
