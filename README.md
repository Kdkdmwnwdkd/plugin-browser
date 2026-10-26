# PluginBrowser 插件浏览器

一个**支持插件机制的安卓手机浏览器**，采用 Kotlin + XML（Material 3）开发。
同时支持两种插件运行时：

- **JS 脚本插件**（userscript 注入式，类 Tampermonkey）——低门槛、热更新、按网站匹配注入；
- **原生插件**（Kotlin/Java，独立 APK + `DexClassLoader` 动态加载）——完整原生能力，可拦截请求、扩展菜单、调用系统 API。

在 MVP 浏览器能力之上，第一版即包含完整功能：多标签、无痕模式、书签、历史、
下载管理、广告拦截、暗色主题、桌面版 UA、外部链接接管等。

---

## 1. 功能总览

### 浏览器核心
| 功能 | 说明 |
| --- | --- |
| 多标签 | 底部工具栏显示标签数，网格切换器（缩略图 + 关闭 + 新建） |
| 标签持久化 | 退出时保存标签 URL，重启恢复；无痕标签不保存 |
| 无痕模式 | 不记录历史、不写 Cookie 持久化策略、紫色徽标标识 |
| 地址栏 | 自动补全建议（书签/历史/搜索，200ms 防抖）、安全锁图标 |
| 搜索 | 百度 / Google / Bing / DuckDuckGo 可切换 |
| 网页交互 | 文件上传（`onShowFileChooser`）、全屏视频、`window.open` 弹窗 |
| 长按菜单 | 保存图片 / 新标签打开图片 / 新标签打开链接 / 复制链接 / 复制文字 |
| 下载 | 系统 DownloadManager 委托，本地数据库记录状态，列表可打开/删除 |
| 错误页 | 本地 HTML 错误页，`prefers-color-scheme` 自动适配深浅色 |
| 返回键 | 全屏退出 → 网页后退 → 退出 Activity |

### 设置项
搜索引擎、主页 URL、JavaScript 开关、第三方 Cookie、广告拦截开关、桌面版 UA、
主题（跟随系统/浅色/深色）、清除浏览数据、关于。

### 内置插件（3 个 JS + 1 个原生）
| 插件 | 类型 | 说明 |
| --- | --- | --- |
| 夜间模式 nightmode | JS | 全站注入 invert + hue-rotate 暗色滤镜，启用次数存 `PB.storage` |
| 净化 purify | JS | 按选择器移除广告位，MutationObserver 防抖监听动态节点 |
| 阅读模式 reader | JS | 浮动按钮 + 正文提取启发式（段落评分）+ 浮层阅读 + 字号记忆 |
| 广告拦截 adblock | 原生 | `assets/blocklist.txt` 约 250 个广告/跟踪域名，`shouldInterceptRequest` 拦截子资源 |

---

## 2. 工程结构（三模块）

```
PluginBrowser/
├── settings.gradle.kts          # 三模块声明 + 仓库（含国内镜像）
├── app/                         # 浏览器宿主（Application/Activity/Fragment/UI）
│   └── src/main/
│       ├── assets/
│       │   ├── js/pb-bridge.js  #   JS API 垫片（PB.* / GM_* 兼容）
│       │   ├── home.html        #   起始页
│       │   ├── plugins/         #   内置 JS 插件（nightmode/purify/reader）
│       │   └── blocklist.txt    #   广告域名名单
│       └── java/com/pluginbrowser/
│           ├── browser/         #   WebView 客户端、多标签、标签切换器
│           ├── data/            #   SQLite（书签/历史/下载）+ 设置仓库
│           ├── download/        #   下载跟踪 + 下载列表
│           ├── plugin/          #   双运行时插件框架（核心）
│           ├── settings/        #   设置页
│           ├── ui/              #   书签/历史
│           └── util/            #   URL 工具
├── plugin-api/                  # 共享接口库（NativePlugin/BrowserHost/...）
└── sample-plugin/               # 原生插件开发范本（独立 APK）
```

**为什么这样拆分**：`plugin-api` 是宿主与原生插件之间的契约。
- `app` 以 `implementation` 依赖它 —— **运行时由宿主提供接口类**；
- `sample-plugin` 以 `compileOnly` 依赖它 —— **插件 APK 不打包接口类**，
  由宿主的 `DexClassLoader` 以「宿主类加载器为父」解析，保证类标识一致。

---

## 3. 构建

### 环境要求
- JDK 17+
- Android SDK：platform-34、build-tools 34.0.0
- Gradle 8.7（已含 wrapper）

### 命令
```bash
# 1. 配置 SDK 路径（或让 Android Studio 自动生成）
echo "sdk.dir=/path/to/your/android-sdk" > local.properties

# 2. 编译宿主 + 示例插件两个 APK
./gradlew assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk            （浏览器，约 6.4 MB）
# sample-plugin/build/outputs/apk/debug/sample-plugin-debug.apk （示例原生插件，约 0.8 MB）
```

> `settings.gradle.kts` 中已将腾讯云 Maven 镜像置于 `google()` / `mavenCentral()`
> 之前，国内网络下载依赖更快；海外用户可自行删去该行。

---

## 4. 安装与体验

1. 安装 `app-debug.apk`；
2. （可选）安装 `sample-plugin-debug.apk` —— 宿主启动时会自动发现它，
   在「菜单 → 插件管理 → 外部原生插件」中看到 **Hello 原生插件**，
   启用后：加载时弹 Toast、溢出菜单多出「和插件打个招呼」、拦截 example.com 跟踪像素；
3. 「菜单 → 插件管理」可启用/停用内置 JS 插件（夜间模式 / 净化 / 阅读模式），
   「设置 → 广告拦截」控制内置原生广告拦截；
4. 「菜单 → 导入」可选择 `.pbp` 插件包安装 JS 插件（见下文格式）；
   仓库 `dist/LinkSwift.pbp` 为预置的网盘直链插件包，可直接导入体验（见 4.5 节）。

---

## 4.5 预置插件包：LinkSwift（网盘直链）

`dist/LinkSwift.pbp` 是把开源项目
[LinkSwift](https://github.com/hmjz100/LinkSwift)（27.6K star 的油猴脚本）
打包成的 .pbp，验证「零改动运行大型真实用户脚本」：

- **能力**：在九大网盘（阿里云盘、百度网盘、天翼云盘、迅雷云盘、夸克网盘、
  123云盘、UC网盘、移动云盘、蓝奏网盘）网页版中解析并显示**直链下载地址**；
- **适配方式**：manifest 声明 40 条 `@match`（runAt=document_start）、
  3 个 `requires`（jQuery 3.6.0 / SweetAlert2 11.4.8 / js-md5 0.7.3）、
  2 个 `resources`（CSS）与 `grant`（http / tabs / clipboard，
  脚本实际调用了这三类能力），原版 user.js 未改一行；
- **使用**：安装浏览器 APK 后，「菜单 → 插件管理 → 导入」选择
  `LinkSwift.pbp`，再打开任一九大网盘网页版即自动生效
  （解析入口通常出现在网盘页面分享/下载按钮旁）。

> 已知限制：`document_start` 在安卓 WebView 中为尽力而为（脚本可能晚于
> 页面首个脚本执行）；`GM_registerMenuCommand` 注册的菜单当前仅记录日志。
> 直链获取依赖网盘网页接口，接口变动时以上游项目更新为准。

---

## 5. 插件运行时架构

```
┌─────────────────────── 宿主 app ───────────────────────┐
│  PluginManager（统一调度）                              │
│   ├─ JS 运行时                                          │
│   │   onPageFinished → jsScriptFor(url)                │
│   │     = pb-bridge.js + 命中的用户脚本（按 @match）      │
│   │   → evaluateJavascript 注入                         │
│   │   页面 JS ⇄ JsBridge(@JavascriptInterface)          │
│   │     storage / http / clipboard / notify / tabs      │
│   │                                                     │
│   └─ 原生运行时                                         │
│       enabledNativePlugins()                            │
│         shouldInterceptRequest（链式，AdBlock 优先）      │
│         onPageStarted / onPageFinished / 下载拦截        │
│         menuItems → 溢出菜单 MENU_PLUGIN_ID_BASE 起      │
│       外部插件：ExternalPluginLoader                     │
│         queryIntentActivities(ACTION) → meta-data ENTRY  │
│         → DexClassLoader(apk, dexDir, null, 宿主 CL)     │
└─────────────────────────────────────────────────────────┘
```

关键设计：
- **JS 插件匹配**：`@match` 模式 `*://*.example.com/*` 转正则
  （`*.` → `(.*\.)?`、`*` → `.*`），仅命中页面注入；
- **JS Bridge 线程模型**：`@JavascriptInterface` 在 JavaBridge 线程执行，
  结果经主线程 `evaluateJavascript` 回传 `window.__PB_REPLY__`；
- **storage 隔离**：每个插件的 KV 存于独立 `pb_plugin_data_$pluginId` 文件；
- **插件发现**（API 30+ 软件包可见性）：宿主清单通过 `<queries>` 声明
  `com.pluginbrowser.plugin.NATIVE` action 才能发现外部插件 APK。

---

## 6. JS 插件开发

### 目录结构
```
my-plugin/
├── manifest.json     # 元数据 + 匹配规则（requires/resources 引用 lib/ 下文件）
├── user.js           # 用户脚本
└── lib/              # 可选：依赖库（jQuery/SweetAlert2/...）与资源文件（CSS/...）
```

### manifest.json
```json
{
  "id": "com.example.myplugin",
  "name": "我的插件",
  "version": "1.0.0",
  "author": "你的名字",
  "description": "插件描述",
  "type": "js",
  "matches": ["*://*.example.com/*"],
  "runAt": "document_idle",
  "grant": ["storage", "http", "notify"],
  "requires": ["lib/jquery.min.js"],
  "resources": { "style": "lib/style.css" }
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一标识，同时作为 storage 隔离键 |
| `matches` | URL 匹配模式数组，支持 `*` 通配符 |
| `runAt` | `document_start`（页面开始加载时即尝试注入，尽力而为）；其他值（默认 `document_end`）在页面加载完成后注入 |
| `grant` | 申请的权限：`http` / `clipboard` / `notify` / `tabs`。桥接层**强制校验**——manifest 未声明对应 grant 时，`GM_xmlhttpRequest`/`GM_setClipboard`/`GM_notification`/`GM_openInTab` 调用会以 `permission denied` 失败（`storage` 无需声明，所有插件可用）。manifest 中旧字段名 `permissions` 同样被接受 |
| `requires` | 预加载的依赖 JS 文件（相对插件目录），在用户脚本**之前**注入，等效于 Tampermonkey 的 `@require`。库会泄漏到页面全局（如 `jQuery`/`$`），宿主机在执行用户脚本后自动还原页面原有全局变量 |
| `resources` | 命名资源（名称 → 插件目录内文件），等效于 `@resource`，用户脚本通过 `GM_getResourceText(name)` **同步**读取 |

### 用户脚本执行方式（沙箱语义）

用户脚本以 `new Function('jQuery', '$', 'Swal', 'md5', 代码).call(window, ...)`
执行：函数体内以形参接收 `requires` 注入的库，**顶层声明不泄漏为页面全局**
（等效 Tampermonkey 沙箱）；库自身泄漏的全局（`jQuery`/`$`/`Swal`/`swal`/`md5`）
在执行前后备份还原，不会覆盖页面自己的 jQuery 等。

### user.js 可用 API（由 pb-bridge.js 注入）

```js
// 存储（按插件隔离，返回 Promise；也可以传回调作为第二个参数）
await PB.storage.set('key', { any: 'json' });
const v = await PB.storage.get('key');
await PB.storage.remove('key');
await PB.storage.keys();

// 跨域 HTTP 请求（原生发起，无 CORS 限制），PB.http.request 是 PB.http 的别名
const resp = await PB.http.request({
  url: 'https://api.example.com/data',
  method: 'GET',            // GET/POST/...
  headers: { 'X-Token': '...' },
  data: 'a=1&b=2',          // 可选，POST/PUT/PATCH 时作为请求体
});
// resp = { status, statusText, headers, body }；失败时为 { error }

// 剪贴板 / 通知 / 新标签
await PB.clipboard.write('文本');
await PB.notify('标题', '内容');
await PB.tabs.open('https://example.com');

// 页面信息（注意是函数）
PB.page.url(); PB.page.title();

// 日志（Logcat 可见）
PB.log('hello');

// 回调风格同样可用（Promise 与回调可混用）
PB.storage.get('key', function (v) { console.log(v); });

// GM_* 兼容（详见下表）
GM_setValue / GM_getValue / GM_getResourceText / GM_info ...
```

> 所有异步 API 返回 Promise（内部经 `window.__PB_REPLY__` 回传），同时保留回调参数，
> Tampermonkey 风格的 `GM_*` 回调写法不需要改。

> **安全边界**：所有桥接调用都会在原生侧校验「该插件是否对当前页面生效」
> （已启用 + JS 类型 + `matches` 命中）。页面脚本即使伪造 `__plugin` 参数，
> 也无法冒用其他插件的 `grant` 与存储。

### GM_* API 兼容清单

| API | 风格 | 说明 |
| --- | --- | --- |
| `GM_setValue` / `GM_getValue` / `GM_deleteValue` / `GM_listValues` | **Promise + 回调** | 等价 `PB.storage.*`，按插件 id 隔离 |
| `GM_xmlhttpRequest(opts, cb)` | **Promise + 回调**（`onload`/`onerror`） | 等价 `PB.http`，原生发起无 CORS 限制 |
| `GM_setClipboard(text)` | **Promise + 回调** | 写剪贴板 |
| `GM_notification(opts)` | 异步 | 系统通知，`opts: {title, text}` |
| `GM_openInTab(url, opts, cb)` | 异步 | 新开标签页 |
| `GM_info` | **同步** | `{ scriptHandler: 'PluginBrowser', version, script: {name, version, author, icon} }` |
| `GM_getResourceText(name)` | **同步** | 读取 manifest `resources` 声明的资源文本（安装时随包分发，嵌入 CSS 等场景适用） |
| `GM_registerMenuCommand(name, fn)` | 同步 | 注册脚本菜单，当前版本仅记录（`PB._menuCommands` + 日志），UI 菜单未开放 |

**迁移提示**：为 Tampermonkey 编写的脚本若使用同步 `GM_getResourceText` /
`GM_info`（常见于把 CSS 嵌进模板字符串的脚本，如 LinkSwift），
在本宿主机可直接运行；使用同步 `GM_getValue` 的脚本需改为回调或 `await` 风格
（本宿主两种都支持，同步取值仍不支持）。

### 打包为 .pbp 安装
```
my-plugin/
├── manifest.json     # 元数据 + 匹配规则（requires/resources 引用 lib/ 下文件）
├── user.js           # 用户脚本
└── lib/              # 依赖库（jQuery/SweetAlert2/...）与资源文件（CSS/...）
    ├── jquery.min.js
    └── style.css
```
把目录打成 zip（`manifest.json` 在 zip 根目录或唯一一级子目录下），
扩展名改为 `.pbp`，通过浏览器「菜单 → 插件管理 → 导入」选择安装。
安装时校验 zip-slip 路径穿越，非 JS 类型拒绝安装；
`requires`/`resources` 声明的文件若缺失，安装同样被拒绝。

---

## 7. 原生插件开发

### 步骤
1. 新建 Android 应用模块（可参考 `sample-plugin`）；
2. `build.gradle.kts` 中：
   ```kotlin
   dependencies {
       compileOnly(project(":plugin-api"))   // 运行时不打包，宿主提供
   }
   ```
3. 实现 `NativePlugin` 接口（见 `plugin-api` 模块）；
4. `AndroidManifest.xml` 声明入口（宿主据此发现插件）：
   ```xml
   <activity android:name=".PluginEntryActivity" android:exported="true">
       <intent-filter>
           <action android:name="com.pluginbrowser.plugin.NATIVE" />
           <category android:name="android.intent.category.DEFAULT" />
       </intent-filter>
       <meta-data
           android:name="com.pluginbrowser.plugin.ENTRY"
           android:value="com.example.myplugin.MyPlugin" />
       <meta-data
           android:name="com.pluginbrowser.plugin.MIN_HOST_VERSION"
           android:value="1" />
   </activity>
   ```
5. 编译安装 APK，宿主启动时自动发现，「插件管理」中出现并可启用。

### NativePlugin 接口能力

```kotlin
interface NativePlugin {
    val metadata: PluginMetadata          // id/name/version/author/description/permissions
    fun onLoad(host: BrowserHost)         // 加载完成，可持有 host
    fun onUnload()
    fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse?
    fun onPageStarted(view: WebView, url: String)
    fun onPageFinished(view: WebView, url: String)
    fun onDownloadStart(url: String, userAgent: String, contentDisposition: String,
        mimeType: String, contentLength: Long): Boolean
    fun menuItems(): List<PluginMenuItem>  // 贡献溢出菜单项
    fun onMenuItemClicked(item: PluginMenuItem): Boolean
}
```

`BrowserHost`（插件可用的宿主能力）：`currentUrl` / `openTab(url, incognito)` /
`showToast(msg)` / `getSetting(key, default)` / `isIncognito`。

### 加载原理
宿主通过 `queryIntentActivities` 发现声明了 `com.pluginbrowser.plugin.NATIVE`
的 APK，从 meta-data 读取实现类全名，用
`DexClassLoader(apkPath, codeCacheDir/pb_dx, null, 宿主类加载器)`
实例化。**父加载器为宿主类加载器**，因此插件代码里的 `NativePlugin`
解析到宿主提供的接口类；插件 APK 自身以 `compileOnly` 依赖 `plugin-api`，
不打包接口类，避免类标识冲突。

---

## 8. 安全说明

- **安装校验**：`.pbp` 解压逐条校验路径，拒绝 `../` 穿越；仅接受 `type=js`；
  `requires`/`resources` 声明的文件必须存在于包内，否则拒绝安装；
  `id` 必须匹配 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`（id 会直接作为磁盘目录名），
  且不得与内置插件同名，避免覆盖或双份注入；
- **桥接来源校验**：`PB.*` / `GM_*` 的每个调用都会回原生侧核对
  「该插件已启用、是 JS 类型、且 `matches` 命中当前页面」，页面脚本伪造
  `__plugin` 也无法冒用其他插件的 `grant` 与存储；
- **依赖读取边界**：`readPluginFile` 以规范路径（canonicalPath）限制在
  插件目录内，用户脚本无法通过 `../` 读取宿主私有文件；
- **内置保护**：assets 内置 JS 插件不可卸载；
- **最小权限面**：JS 插件的跨域 HTTP、通知等能力均经由显式 `grant` 声明，
  桥接层按插件 id 隔离存储；
- **原生插件权限**：继承自其 APK 自身申请的 Android 权限，安装时用户可见；
- **已知边界**（教学项目定位）：原生插件加载未做签名校验，
  生产环境应增加「仅信任签名一致的插件 APK」策略。

---

## 9. 目录速查（关键源码）

| 关注点 | 文件 |
| --- | --- |
| 双运行时调度 | `app/.../plugin/PluginManager.kt` |
| JS 桥接 | `app/.../plugin/js/JsBridge.kt` + `assets/js/pb-bridge.js` |
| 原生插件加载 | `app/.../plugin/native/ExternalPluginLoader.kt` |
| 接口契约 | `plugin-api/.../NativePlugin.kt` |
| 广告拦截示例 | `app/.../plugin/native/AdBlockPlugin.kt` |
| 多标签管理 | `app/.../browser/TabManager.kt` |
| 主界面 | `app/.../browser/BrowserFragment.kt` |

---

## 10. 应用内更新

设置 → 关于 → **检查更新**：客户端读取仓库根目录的 `version.json`，
与自身 `versionCode` 比较；有新版则弹出版本说明，确认后多源下载、校验 SHA-256，
再拉起系统安装器。

清单地址按顺序尝试（国内优先走加速代理）：

```
https://gh-proxy.com/https://raw.githubusercontent.com/<owner>/<repo>/main/version.json
https://ghfast.top/https://raw.githubusercontent.com/<owner>/<repo>/main/version.json
https://raw.githubusercontent.com/<owner>/<repo>/main/version.json
```

**发布新版本只需三步**（不需要改代码）：

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`，编译出 APK；
2. 建对应的 Release，把 APK 传成附件；
3. 更新仓库根的 `version.json`：`versionCode`、`versionName`、`size`、`sha256`、`apk` 链接列表。

老版本客户端下次「检查更新」即可看到新版本。`apk` 数组支持多个源，
客户端会按顺序尝试，任一成功且 SHA-256 匹配即采用。
