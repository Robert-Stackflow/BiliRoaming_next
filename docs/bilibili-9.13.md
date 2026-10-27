# 哔哩哔哩 9.13.0 适配

基于 `ltw253/BiliRoaming_next` 的 `511e0bbba03355be8d5e3045faf216efe80e0537`，该分支包含原始 `7c792dc8fe` 的历史。
目标客户端为 `tv.danmaku.bili` 9.13.0（9130500），交付的整合包使用官方 arm64 客户端和 JingMatrix LSPatch v1.2-487。

## 设置

以下设置位于哔哩漫游设置中；修改后重启客户端生效。净化和自动最高画质默认开启，HDR、杜比视界默认关闭，半屏和全屏均默认选 SDR。

| 设置 | 键 | 行为 |
| --- | --- | --- |
| 打开视频优先最高画质 | `prefer_highest_quality` | 同时处理详情页内嵌/缓存的播放响应和独立播放请求；显式绑定初始媒体流及正在复用的播放器，跳过下载，保留后续手动选档 |
| 同分辨率优先最高帧率 | `prefer_highest_frame_rate` | 优先分辨率，其次按实际片源元数据比较标称帧率，最后比较档位；避免 58.824/59.94 与 60 的微小差异误弃 HDR 档位 |
| 首选、备选画质 | `preferred_video_quality` / `fallback_video_quality` | 首选可用则选首选，否则备选，再回退到最高可用档；默认均为最高 |
| 8K / HDR / 杜比视界 | `enable_8k_quality` / `enable_hdr_quality` / `enable_dolby_vision` | 8K 默认请求；HDR 与杜比视界默认不自动选择，可分别启用；明确手动选择不受初始自动选档覆盖 |
| 自动最高音质 | `auto_audio_quality` / `prefer_lossless_audio` / `prefer_dolby_audio` | 使用实际返回的音轨，默认优先杜比全景声，其次无损，再回退到普通音频 |
| 视频 / 直播编码偏好 | `preferred_video_codec` / `preferred_live_codec` | 默认自动，支持 AV1 / HEVC / AVC；视频在该画质有首选编码时优先使用，缺失则保留可播回退；直播沿用原播放器的能力检测 |
| 直播最高画质 / 线路 | `prefer_highest_live_quality` / `preferred_live_line` | 默认最高；首选主线或备用线路，保留其他线路供原播放器回退；关闭最高后沿用已有直播画质设置 |
| 移除暂停广告 | `remove_pause_ad` | 在暂停广告倒计时创建前关闭入口，并拦截暂停类型的广告面板 |
| 隐藏视频提及 | `remove_video_mentions` | 在 9.13 的页面组件工厂中使用空列表路径，移除标题、卡片和占位；兼容旧版 protobuf 模块过滤 |
| 移除开屏广告 | `remove_splash_ad` | 覆盖 Gson/Kotlinx 响应以及从磁盘恢复的开屏模型 |
| 隐藏播放器听视频按钮 | `hide_player_listen` | 从顶部工具栏的动作列表移除听视频入口 |
| 隐藏播放器竖屏播放按钮 | `hide_player_portrait` | 关闭详情页竖屏入口配置，兼容旧播放器的入口视图 |
| 净化游戏中心 | `purify_game` | 过滤首页游戏入口，不再要求同时开启隐藏功能 |
| 屏蔽视频详情页广告 | `block_view_page_ads` | 额外清空播放器下方广告卡片的两个数据来源，覆盖刷新后的展示 |
| 视频详情动画 | `video_detail_transition` | 默认不修改；可选择恢复系统默认，覆盖普通详情页和连播详情页 |

最高画质仍受账号权限、视频片源和实际返回的播放资源限制。关闭最高画质开关后，恢复原有半屏、全屏画质设置逻辑。
最高画质模式同时禁用半屏画质替换和 DASH 自动降档；网络较慢时可能需要更多缓冲。明确的手动低画质选择仍保留。
初始原生媒体资产的重试候选列表也会过滤未启用的 HDR / 杜比视界档位，防止解码或网络重试重新切回这些片源。手动选择的播放响应保留原候选列表。
菜单样式修改已撤回，分享、推荐列表和图片页面继续使用原有菜单。
Hero 动画及其设置项已删除。旧版选择 Hero 的配置按“不修改”处理，打开设置时迁移到默认值。导航模块只在选择“恢复系统默认”时启用。

## 稳定性

- 开屏过滤返回新的可修改列表。9.13 的启动更新流程会向列表追加数据，返回 Kotlin `emptyList` 会导致启动崩溃。
- 顶部入口在布局初始化时使用基类 `View.setVisibility` 隐藏，避免提前调用 Gemini 控件的重写方法而访问尚未初始化的控制器。
- 小米设备覆盖安装后，系统预加载的类加载器可能保留旧安装目录。兼容处理在宿主实际加载某个原生库前，按官方 9.13 arm64 库的 `DT_NEEDED` 依赖关系，从当前安装目录加载它所需的附属库；不加载整张表，不处理系统库。
- 9.13 的“视频提及”组件工厂经过混淆，本次固定适配该版本。升级客户端后需要重新检查此入口。
- `assets/bili-9.13-arm64-hookinfo.pb` 是该官方客户端生成的类名、方法名适配数据，不包含账号配置。仅在包名、版本号及原版 APK 的大小/CRC 指纹匹配时使用，避免首次启动在主线程扫描 32 个 dex 而触发系统超时；其他版本继续原有发现流程。原版 SHA-256 为 `1a384664f47d76259907e91e22a2f072093e1466f73927f60fd576b77636212f`。

## 构建

使用 JDK 17、Android SDK 35、Build Tools 35.0.1 和 CMake 4.1.x。默认 NDK 为 29.0.14206865；本次本地构建使用完整安装的 NDK 30.0.14904198，通过属性覆盖：

```sh
git submodule update --init --recursive
./gradlew :app:testDebugUnitTest :app:assembleRelease \
  -PlocalNdkVersion=30.0.14904198 -PappVerName=9.13-local-r33 --no-daemon
```

签名属性为 `releaseStoreFile`、`releaseStorePassword`、`releaseKeyAlias`、`releaseKeyPassword`，通过本地 Gradle 属性或 `ORG_GRADLE_PROJECT_` 环境变量提供。不要提交密钥、密码、原版 APK、反编译结果或真机日志。

交付使用优化的 Release 模块。Debug 原生扫描在本次设备上曾触发启动超时，不能用 Debug 的运行表现代替 Release 验证。

## 验证

11 项 JVM 回归测试通过，覆盖首个请求与手动选档、缓存播放、预加载交错、本地下载、开屏列表可变性，以及视频分辨率/帧率、首选回退、SDR 默认、音轨优先级。
r33 已在同一真机覆盖安装，完成旧选项迁移、详情页打开和返回检查，未记录到致命异常或 Hook 错误。Release 模块确认不再包含此前的快照动画、共享元素或渲染线程动画实现；签名、非 debuggable 和整合包内嵌模块一致性检查通过。

此前版本 Android 16 / 小米 23127PN0CC 真机验证：暂停等待 8 秒未出现广告或倒计时；视频提及、顶部听视频/竖屏入口和播放器下方广告已过滤。菜单样式继续保持原样。

播放器输出帧率以活动 `IjkMediaPlayer` 的 decode/output fps 为准。`CCodec` 初始化时打印的 frame-rate=1/30，以及播放器项目对象的缓存 quality，均不能单独证明实际播放档位。

默认 SDR 真机验证：BV1oRwVe8E1z 冷启动首次半屏及重复进入均为 1920×1080、实际约 60fps；《文字大战》3840×1920 SDR、实际约 119fps；ep1205042 3840×2160 SDR、实际约 60fps。BV1tFZZBQE57 默认 3840×2160 SDR、约 60fps；BV1Sf4y1q7H9 默认 3840×2160 SDR，片源为约 24/25fps，不存在可选择的 60fps 档。BV12Yf5BAEyb 默认 3840×2160 SDR、约 60fps，实际活动音轨 30250；BV1XN411g7SA 活动音轨为无损 30251。默认 SDR 的实际输出 `color-transfer=3`，与此前可选 HDR 的 PQ/HLG 不同。
全屏验证使用 BV1tFZZBQE57，屏幕实际进入 2670×1200 横屏，仍选中 4K SDR、约 60fps 的源档；起播短采样输出约 53fps，未做长期稳定帧率评测。最终 Release 整合包与模块的 v2 签名校验通过、内嵌模块字节完全匹配，二者均未启用 debuggable，已覆盖安装并保留登录。

HDR 开启的测试版本已验证：《文字大战》BV1rp4y1e745 半屏 3840×1920 HDR、源 120fps，活动播放器输出约 123fps；纪录片 ep1205042 半屏 3840×2160 HDR、源 60fps，输出约 59fps；BV12Yf5BAEyb 使用实际 `video/dolby-vision` 解码、3840×2160 有效画面约 60fps，并选中杜比音轨 30250。BV1XN411g7SA 返回无损音轨 30251。上述结果用于证明可选 HDR/DV 功能，不表示最终默认开启 HDR。

用户提供的 BV1tFZZBQE57、BV1Sf4y1q7H9 和 ep1205042 在本次账号/客户端实际返回的最高档为 4K HDR，没有返回 8K 或杜比视界档位；标题不能证明播放源规格。本次尚未完成真实 8K 播放验证。直播房间 1883358196 实际 `accept_qn` 最大为 10000，自动请求最高后回退到该原画档，不能用全局 `g_qn_desc` 声称房间提供 4K。

## 参考项目

参考 [AHCorn/Bilibili-Auto-Quality](https://github.com/AHCorn/Bilibili-Auto-Quality)，主脚本 6.0.1，blob `c1b72618804acd7750a9659db8767d6ec400674f`，GPL-3.0。原项目使用网页按钮文字和 DOM 交互；本适配使用 Android 原生档位、可播资源与帧率元数据，避免其 issue #35 所述“大视界”等标签变化影响选档。浏览器 localStorage、网页面板和 UA 伪装不直接移植到 Android。请求更高档位仍需账号权限和客户端解码支持，不修改会员状态。

尚未实测 Android 15 和 Android 17；不能据此宣称已验证全部系统版本或全部客户端功能。
