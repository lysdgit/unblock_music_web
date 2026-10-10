# 解灰音乐 · Android 客户端（WebView 壳 + 后台播放保活）

把网页版 **https://yy.lys.cn.eu.org/** 打包成原生 APK，并解决 **「后台播放几分钟自动暂停」** 的问题。
APK 不依赖本地环境，**全部由 GitHub Actions 云端编译**。

---

## 一、先搞清楚：为什么后台播几分钟就停了？

打开网页源码可以看到站点作者自己的注释：

```js
/* MediaSession：锁屏/通知栏播放控制 + 进度上报。
   手机 Chrome 直接用即可息屏续播；WebView 打包端需配合媒体前台服务 */
```

也就是说，网页本身是没问题的，**问题出在 WebView 壳上**。具体是三个原因叠加：

| # | 原因 | 现象 | 本工程的解决方式 |
|---|------|------|------------------|
| 1 | **系统冻结进程**。<br>Android 8.0 起，App 退到后台即被标记为「可缓存进程」，Android 11+ 的 App Freezer 会在几分钟内把它冻住 | 播 2~5 分钟后无声、通知栏消失 | `PlaybackService`：拉起 **mediaPlayback 类型的前台服务** + 常驻通知，把进程提升为前台进程，系统不再冻结 |
| 2 | **WebView 被主动暂停**。`Activity.onPause()` 里若调用 `webView.onPause()`，会连 JS 定时器和 `<audio>` 一起停掉 | 一按 Home 键就停 | `MainActivity.onPause()` 里**刻意不调用** `webView.onPause()` |
| 3 | **返回键销毁了 Activity** | 按一下返回键音乐就断 | 返回键改为 `moveTaskToBack(true)`，Activity 与 WebView 继续存活；只有从「最近任务」划掉才真正退出 |

另外补了三件事：

- **MediaSession**：满足 Android 14 对 `mediaPlayback` 前台服务的强制要求，同时让锁屏、通知栏、耳机线控都能控制播放/切歌；
- **PARTIAL_WAKE_LOCK**：息屏后 CPU 仍能跑，保证「一曲结束 → 自动下一首」的逻辑不断；
- **AudioFocus**：来电话或被其它音乐 App 抢占时自动暂停，结束后恢复播放。

> 站点是单页 PWA，播放就靠一个 `<audio id="audio">`。原生侧的业务指令（播放/暂停/上一首/下一首）全部通过 `evaluateJavascript` 直接操作这个元素，不依赖网页内部函数作用域，升级网页也不会失效。

---

## 二、目录结构

```
.
├── .github/workflows/build-apk.yml      ← GitHub Actions 自动编译流水线
├── settings.gradle
├── build.gradle
├── gradle.properties
└── app
    ├── build.gradle                     ← 包名 / 应用名 / 版本号 / 编译 SDK
    ├── gen_icons.py                     ← 图标生成脚本（可选，见第五节）
    └── src/main
        ├── AndroidManifest.xml          ← 权限、前台服务类型声明
        ├── java/com/jiehui/music
        │   ├── MainActivity.java        ← WebView 壳 + 后台保活 + JS 桥
        │   ├── PlaybackService.java     ← 媒体前台服务（核心）
        │   └── WebViewHolder.java       ← WebView 弱引用，供服务下发指令
        └── res                          ← 图标、主题、文案
```

---

## 三、怎么用：上传到 GitHub，自动出 APK

### 第 1 步：创建仓库

在 GitHub 上新建一个仓库（Public / Private 都行），**不要**勾选添加 README / .gitignore。

### 第 2 步：上传本目录

方式 A —— 网页上传（最省事）：

1. 打开新建好的仓库页面，点 **Add file → Upload files**
2. 把本目录**里面的全部文件和文件夹**拖进去
   （`.github`、`app`、`settings.gradle` 等，注意 `.github` 是隐藏目录，要确保一起传上去）
3. 点 **Commit changes**

方式 B —— 命令行：

```bash
cd 本目录
git init
git add .
git commit -m "first commit"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

### 第 3 步：等自动编译

推送成功后 → 进入仓库的 **Actions** 标签页 → 会自动跑一条名为 **Build APK** 的任务（约 3~6 分钟）。

也可以在 Actions 页面点 **Run workflow** 手动触发。

### 第 4 步：下载 APK

编译完成后，进入这次运行的结果页，页面底部 **Artifacts** 区域下载：

- `jiehui-music-debug` —— 免签名 Debug 包，**可直接安装**（推荐先用这个）

解压后得到 `jiehui-music-debug.apk`，传到手机安装即可。

> 想要一个稳定的下载直链：给仓库打个 tag（如 `v1.0.0`）再推送，工作流会自动创建 **Release** 并附上 APK。

---

## 四、装到手机后必做的两件事

安装后应用会**自动引导**你完成，但如果你跳过了，请手动补上，否则国产 ROM 上仍可能被后台杀掉：

1. **允许通知**
   设置 → 应用 → 解灰音乐 → 通知 → 允许。
   没有通知权限，锁屏/通知栏就不会出现播放控制卡片，系统也更容易回收它。

2. **加入电池优化白名单**（关键）
   应用首次启动会弹窗引导，点「去设置」→ 在列表里找到「解灰音乐」→ 选择 **不优化 / 允许后台活动**。

   国产 ROM 还需要额外一步（各家叫法不同）：

   | 品牌 | 路径 |
   |------|------|
   | 小米 / 红米 | 设置 → 应用设置 → 应用管理 → 解灰音乐 → **省电策略 → 无限制**，并开启**自启动** |
   | 华为 / 荣耀 | 手机管家 → 应用启动管理 → 解灰音乐 → 关闭「自动管理」，三项全部手动允许 |
   | OPPO / 一加 | 设置 → 电池 → 应用耗电管理 → 解灰音乐 → 允许**后台运行** |
   | vivo / iQOO | 设置 → 电池 → 后台高耗电 → 允许解灰音乐 |
   | 三星 | 设置 → 电池 → 后台使用限制 → 从「休眠应用」中移除 |

**退出方式**：按返回键只是退到后台继续播放；想彻底退出，从「最近任务」里把应用划掉即可。

---

## 五、想改点什么？

| 想改的 | 改哪里 |
|--------|--------|
| **网址** | `app/src/main/java/com/jiehui/music/MainActivity.java` 顶部的 `START_URL` 和 `HOME_HOST` |
| **应用名** | `app/src/main/res/values/strings.xml` 里的 `app_name` |
| **包名** | `app/build.gradle` 的 `namespace` + `applicationId`，同时把 `app/src/main/java/com/jiehui/music/` 目录改名为对应路径，并把三个 `.java` 文件第一行的 `package` 改掉 |
| **版本号** | `app/build.gradle` 的 `versionCode` / `versionName` |
| **minSdk / targetSdk** | `app/build.gradle`（改动 targetSdk 时记得同步改 `.github/workflows/build-apk.yml` 里的 `platforms;android-XX`） |
| **图标** | 自适应图标直接改 `res/drawable/ic_launcher_background.xml`、`ic_launcher_foreground.xml`；传统 PNG 图标运行 `python app/gen_icons.py` 重新生成（纯标准库，无需装任何依赖） |

改完 `git push`，GitHub Actions 会自动重新编译。

---

## 六、可选：产出正式签名包

Debug 包已经够日常用。如果你想要正式签名包（体积更小、无 debug 标记）：

1. 本地生成一个签名文件：

   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
           -validity 10000 -alias jiehuimusic
   ```

2. 把它转成 Base64（Windows PowerShell）：

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content keystore.txt
   ```

3. 在仓库 **Settings → Secrets and variables → Actions** 里新增 4 个 Secret：

   | Secret 名 | 值 |
   |-----------|-----|
   | `KEYSTORE_BASE64` | `keystore.txt` 的全部内容 |
   | `KEYSTORE_PASSWORD` | 你设置的 keystore 密码 |
   | `KEY_ALIAS` | `jiehuimusic` |
   | `KEY_PASSWORD` | 你设置的 key 密码 |

   配好后，工作流会自动多编出一个 `jiehui-music-release` 签名包；没配就自动跳过，不影响 Debug 包。

> ⚠️ 签名文件和密码务必自己保管好。**请把 `release.jks` 加到 `.gitignore`（已经默认加好了），绝对不要提交到仓库。**

---

## 七、常见问题

**Q：Actions 报错 `Failed to find package 'platforms;android-34'`**
A：`app/build.gradle` 的 `compileSdk` 与工作流里的 `platforms;android-XX` 必须一致，改的时候一起改。

**Q：装到手机提示「解析包时出现问题」**
A：确认下载的是 `.apk` 而不是 `.zip`。GitHub Artifacts 下载下来是 zip，需要先解压。

**Q：还是会被后台杀掉**
A：先确认通知栏确实常驻着播放卡片（说明前台服务起来了）；然后按第四节的表格把该品牌的省电策略/自启动都放开。国产 ROM 的管控是最常见的原因。

**Q：网页版正常，但 App 里某些歌曲播不了**
A：多为音源直链是 `http`，工程已开启 `usesCleartextTraffic` 与混合内容放行；若仍失败，多半是站点自身的换源逻辑问题（`加载中…（灰色歌曲正在换源）`）。

**Q：想加个「真正退出」的菜单**
A：可以在 `MainActivity` 里加一个长按返回键 → `PlaybackService.stop(this); finish();` 的处理。

---

## 八、技术要点速查

- 目标：`compileSdk 34` / `targetSdk 34` / `minSdk 21`（Android 5.0 ~ 15 全覆盖）
- 语言：Java，**零 androidx 依赖**，纯平台 API，编译快、故障点少
- 前台服务：`android:foregroundServiceType="mediaPlayback"`
  权限：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `WAKE_LOCK` + `POST_NOTIFICATIONS`
- 编译环境：JDK 17 + Gradle 8.9 + AGP 8.5.2
