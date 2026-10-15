# 拾课

简洁的原生 Android 课程表，课程在本机保存，日常查看无需联网。使用 Kotlin 与 Jetpack Compose，支持 Android 8.0 及以上。

当前版本为 1.5.0。本机安装包位于 `dist/拾课-1.5.0-debug.apk`，源码压缩包位于 `dist/拾课-1.5.0-源码.zip`；`dist/` 不纳入 Git，拉取仓库后可以自行构建安装包。

## 继续开发

仓库：[KhronicleDavid/shike-timetable](https://github.com/KhronicleDavid/shike-timetable)。

```powershell
git clone https://github.com/KhronicleDavid/shike-timetable.git
cd shike-timetable
```

可以使用 Android Studio 打开项目，或按下方「构建」说明配置开发环境。修改后先构建和运行相关测试，再提交、推送到 `main`。课表、教务登录会话和签名密钥只保存在本机；仓库包含虚构示例与脱敏测试数据。

主题入口为 `app/src/main/assets/themes/catalog.json`，每个主题有独立的 `theme.json` 和图片目录。立绘、分时语录、画册与配色从配置读取，方便增删内容；开发步骤和可复制模板见 [主题与角色素材维护](docs/主题与角色素材维护.md)。

下一角色主题「和泉纱雾」的 [126 条原创分时段陪伴语](docs/和泉纱雾陪伴语.md) 已整理为 [文案草稿包](docs/theme-drafts/sagiri/README.md)，等待用户提供图片后接入，当前安装包尚不包含此主题。

![阳茉莉主题周课表](screenshots/阳茉莉-课表-浅色.png)

## 功能

- 周课表：自动计算教学周、切换周次、突出今天、显示今日课程。
- 课程管理：添加、编辑、删除、搜索课程；支持教师、教室、备注、课程配色。
- 排课规则：1–12 节、1–30 周、单双周；准确检测实际教学周内的课程冲突。
- 学期设置：开学周一、学期周数、每日作息、周末显示。
- 外观：可在设置中切换「阳茉莉主题」和「简约主题」，应用和小组件共用整体主题；另支持浅色、深色和跟随系统。真白素材留待后续补充。
- 角色陪伴：原画与十五幅重绘，早晨、午间、夜晚各四幅分时插画；120 条依据指定角色 skill 新写的陪伴语，支持关闭展示。
- 点击互动：点击角色或语录区，只从实际早晨、午间或夜晚的对应池中换一句和一幅图，优先避开最近五句；切换页面、重建界面和普通小组件刷新保留选择，到下一半小时恢复自动轮换。换句提示在首次点击后永久隐藏，应用与小组件共用该状态。
- 桌面小组件：今日课程、教室、上课时间；课程多时上下滑动，可铺满横向并调节高度，点击课程打开详情，保存课表后自动刷新。
- 角色画册：在设置中查看全部十六幅形象，包括原画、迎接、学习、休息与十二幅分时插画。
- 数据：本机保存，通过系统文件选择器导入与导出 JSON 备份。
- 教务导入：电子科技大学官网内登录、读取课表、预览确认；也支持课表 HTML 文件和受支持的树维课表 JSON。

首次打开提供可编辑的示例课程。可以在「设置 → 清空课表」删除示例，再填写自己的课程；清空前可先导出备份。

## 构建

开发工具安装在 `E:\AndroidDev`，不改动系统的 Java 环境。需要迁移时可以传入其他路径。

构建脚本会为中文目录创建指向本项目的 ASCII 路径 Junction，解决 Windows 测试类路径编码问题，生成文件仍保存在本目录。

```powershell
# 下载并配置 JDK 17、Gradle 8.9、Android SDK 35
.\scripts\setup-environment.ps1

# 生成调试 APK 并运行业务测试
.\scripts\build.ps1

# 自定义工具目录
.\scripts\build.ps1 -DevRoot D:\AndroidDev

# Android 设备连接后运行界面测试与静态检查
.\scripts\build.ps1 -Tasks @('connectedDebugAndroidTest', 'lintDebug')
```

APK 默认位于 `app\build\outputs\apk\debug\app-debug.apk`。这是调试签名的本地试用版本；正式分发前需配置自己的 release 签名。

也可使用 Android Studio 打开本目录。将 Gradle JDK 设置为工具目录中的 JDK 17，并在 `local.properties` 配置 Android SDK 路径。项目已包含 Gradle Wrapper。

## 使用说明

1. 在「设置 → 当前学期」选定第 1 周的周一，填写总周数。首页随后自动计算当前周。
2. 点右上角 `+` 添加课程，选择星期、节次、上课周与单双周。
3. 点课表色块或课程列表卡片编辑，删除前需确认。
4. 点「第 N 周」可以跳转教学周，左右箭头逐周浏览。
5. 通过「设置 → 导出备份」保存 JSON；导入前显示确认，替换当前课程与设置。

一条课程记录表示一个固定的每周上课时段。同一门课程一周上两次时，分别添加两条排课记录。

课程管理无需注册拾课账号。教务导入使用联网权限，文件导入导出使用 Android 系统文件选择器，无需申请存储权限。重装或清除应用数据前请导出备份。

## 从电子科技大学教务系统导入

1. 打开「设置 → 从教务导入 → 打开学校教务系统」。入口为 [电子科技大学教务系统](https://eams.uestc.edu.cn/eams/)。
2. 在学校网页内自行登录，进入「课程管理 → 我的课表」，或点应用上方「我的课表」直接进入。选择需要的学期，并显示全部教学周。
3. 点应用底部「读取课表」，检查课程、教师、教室、星期、节次与周次。
4. 核对学期名称、第 1 周的周一和总周数。具体上课时间沿用现有作息，可展开查看，之后在「设置 → 上课时间」调整。
5. 点「确认替换并导入」。只有确认后才替换当前课程，取消或读取失败不会改变原课表。

Android 应用需要在自己的网页登录；电脑 Edge 登录状态不会搬入应用。拾课不采集登录表单中的账号或密码。登录会话仅在本机用于连接学校官网；兼容页面加载器使用本应用 WebView 的会话加载学校 HTTPS 页面，关闭导入界面会清理该会话。原始网页和会话不写入课表备份。

1.1.1 版默认开启「电脑版」：以当前 WebView 内核对应的桌面 Chrome 标识请求网页，并让教务页面使用 1280 像素的桌面布局，可双指放大浏览。上方「电脑版」按钮可切回手机布局，切换会刷新当前页面；不会清理登录会话。学校认证页面沿用自己的布局视口。设置依据见 [Android WebSettings](https://developer.android.com/reference/android/webkit/WebSettings)。

如果访问验证停留、网页空白或手机 WebView 无法登录，可在正常浏览器登录并打开完整学期课表，保存课表 HTML，传到手机后选择「从课表文件导入」。文件限制为 2 MB，支持 UTF-8、带 BOM 的 UTF-16，以及声明 GBK/GB2312/GB18030 的 HTML；拾课备份仍使用原有「导入备份」入口。

解析优先读取网页已经计算好的教学周，支持单双周、离散周次、分段换教室、同格多门课程和未指定教室的课程。应用把离散周次拆成精确排课记录，因此排课条数可能多于课程门数。无法完整表达的第 13 节及以上或第 31 教学周及以上会拒绝导入，不截断安排。

已通过用户登录后的 Edge 真实页面核实结构。之后在 Android 11 模拟器复现首页缺少脚本和样式的问题，并通过兼容页面加载恢复资源；用户随后确认课表能够读取。1.2.0 包含这一修复；仅教务整页 GET 使用兼容加载，登录 POST、AJAX 和子资源仍由 WebView 处理，证书检查和学校域名限制保留。研究依据与限制见 [教务导入研究](docs/教务导入研究.md)。

角色展示偏好在本机独立保存，课表 JSON 格式保持兼容。关闭「阳茉莉陪伴」后，首页保留今日安排，课程页和空状态恢复纯信息布局；画册仍可由你主动打开。

角色来源、素材及设计说明见 [阳茉莉视觉设计](docs/阳茉莉视觉设计.md)。原立绘由用户提供；九幅适配插画通过内置 imagegen 工具参考原立绘重绘，角色与原作版权归 SMEE。

小组件的添加、大小调整、更新限制与时段规则见 [桌面小组件与分时段陪伴](docs/桌面小组件与分时段陪伴.md)。可从「设置 → 桌面今日课表」添加，也可长按手机桌面空白处，从小组件列表中选择拾课。台词原文见 [阳茉莉陪伴语](docs/阳茉莉陪伴语.md)。

## 项目结构

- `app/src/main/java/com/shike/timetable/MainActivity.kt`：周课表、课程列表与应用状态。
- `app/src/main/java/com/shike/timetable/ui`：主题、编辑页与设置页。
- `app/src/main/java/com/shike/timetable/data`：模型、日期规则、冲突检测、本地存储与严格导入校验。
- `app/src/main/java/com/shike/timetable/importer`：不执行网页脚本的教务课表解析器。
- `app/src/test`：日期、单双周冲突、JSON 校验与持久化测试。
- `app/src/androidTest`：真实 Android 上的主要操作流程测试。
- `examples/示例课表.json`：可导入的示例备份。

## 验证

已通过 67 项业务与解析单元测试、12 组 Android 测试。除课程管理、教学周和主题外，还覆盖脱敏真实课表逐周逐节比对、导入预览与重建、确认替换、取消和读取失败保留原数据，以及真实 WebView 上的 DOM 提取、旧回调取消和电脑/手机响应式布局切换。Android 测试在独立的 Android 11 / API 30 模拟器上运行。

APK 已实际安装并启动，Android Lint 检查为 0 个错误。详细结果见 `验证记录.md`。

## 开源参考

周视图的时间轴与按天排列思路参考 [android-week-view](https://github.com/tobiasschuerg/android-week-view)，懒加载课程网格的交互参考 [LazyTimetable](https://github.com/MoyuruAizawa/LazyTimetable)。本项目独立实现课表 UI 与业务规则，没有复制这些项目的代码或素材。

教务格式参考学校公开的 [TaskActivity.js](https://eams.uestc.edu.cn/eams/static/scripts/course/TaskActivity.js) 和开源项目 [uestc-coursetable-parser](https://github.com/Saafo/uestc-coursetable-parser)。解析器独立实现，不复制这些项目代码；源码中的课表样本已脱敏或完全虚构。

运行依赖使用官方发布的 Kotlin、AndroidX、Jetpack Compose 与 Material 3；版本在 Gradle 文件中固定。构建脚本使用官方仓库，并提供阿里云镜像备用地址。
