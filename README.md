# OpenClaw for Android

一个**极轻量**的安卓 AI 助手客户端：把任意大模型 API 接到手机上，并通过微信官方的 ClawBot 插件，让你直接在微信里和自己的模型对话。

- 安装包体积：**约 100 KB**
- 系统要求：**Android 10+（API 29）**，兼容鸿蒙 2.x – 4.x
- 依赖：无 AndroidX、无 OkHttp、无 Gson、无图片库；唯一第三方库是 ZXing（仅二维码编解码，R8 裁剪后只剩 QR 相关类）
- **不内置任何 API Key**——所有模型凭据由使用者自行填写，只保存在本机

## 它能做什么

| 能力 | 说明 |
|---|---|
| 多厂商模型 | 内置 DeepSeek / 智谱 GLM / Kimi / 火山方舟（豆包、DeepSeek V4.1）/ 通义千问 / 硅基流动 / OpenAI / Gemini / Claude 预设，支持 OpenAI 兼容、Anthropic、Gemini 三种协议 |
| 失败自动切换 | 模型按列表顺序组成调用链，报错 / 超时 / 限流自动切下一个 |
| 图片对话 | App 内和微信里都能发图，走多模态接口（OpenAI `image_url` / Anthropic `base64` / Gemini `inline_data`） |
| 微信对话 | 通过微信官方「ClawBot」插件扫码绑定，之后直接在微信里和模型聊天、发图片 |
| Token 统计 | 每条回复标注 输入/输出/合计；用量页有总量、按模型分布、最近调用记录 |

**微信侧的边界**：个人微信号没有开放接口，本应用不做也做不到「读取聊天记录 / 自动操控微信」。微信里的对话能力完全基于微信官方的 ClawBot 插件，属于官方开放通道。

## 安装与使用

1. 从 [Releases](../../releases) 下载 `OpenClaw.apk` 安装（需允许安装未知来源应用）
2. 「模型」页 → 添加模型 → 选预设填入你自己的 API Key（不填 Key 不会参与调用）
3. 「微信」页 → 生成二维码 → 微信扫一扫并确认 → 微信聊天列表出现「微信 ClawBot」，发消息即可
4. 后台回复依赖前台服务：按「微信」页的引导，在系统设置里允许 OpenClaw 后台运行 / 自启动（华为、鸿蒙等国产系统尤其需要）

## 主要功能与界面

- **对话**：流式输出、多模态、每条消息底部显示所用模型与 Token 用量
- **模型**：调用优先级列表、↑↓ 调序 / 置顶、启用停用、内置厂商预设
- **用量**：累计 Token（输入 / 输出 / 请求次数）、按模型分布、最近调用
- **微信**：ClawBot 扫码绑定、连接状态自检、运行日志（方便排查后台问题）
- **设置**：系统提示词、温度 / 最大输出 / 上下文条数（滑轨档位）、流式与自动切换开关

## 项目结构

```
openclaw-android/
├── app/
│   ├── AndroidManifest.xml
│   ├── res/                       # 仅图标 + 名称，资源极少
│   └── java/com/openclaw/android/
│       ├── MainActivity.java      # 单 Activity + 5 Tab + 手绘导航图标
│       ├── core/
│       │   ├── ApiClient.java     # 三协议调用、SSE 流式解析、失败切换、多模态请求体
│       │   ├── ClawBot.java       # 微信 ClawBot 官方协议：二维码登录 / 收消息 / 发消息 / 图片下载解密
│       │   ├── ClawBotService.java# 前台服务：长轮询收消息 → 调模型 → 回微信
│       │   ├── BootReceiver.java  # 开机自启
│       │   ├── ModelConfig.java   # 模型配置（id / 协议 / 地址 / Key / 启用）
│       │   ├── Store.java         # 模型链 / 设置 / 历史 / 用量 / 厂商预设，SharedPreferences 持久化
│       │   ├── UsageStats.java    # 累计统计（合计恒等于输入+输出，保证口径自洽）
│       │   ├── WeChat.java        # （保留）企业微信群机器人 / Server酱 推送
│       │   ├── QrCode.java        # ZXing 二维码生成
│       │   ├── ImageStore.java    # 相册图片压缩 / base64 / 缩略图
│       │   └── Sync.java          # OC1: 配置同步码（gzip + base64）
│       └── ui/                    # 全部界面（纯代码绘制，无 XML 布局）
│           ├── SegmentedRail.java # 自绘滑轨控件（开关 / 档位选择）
│           ├── ChatView.java      # 对话页
│           ├── ModelsView.java    # 模型管理
│           ├── UsageView.java     # 用量统计
│           ├── WeChatView.java    # 微信连接与诊断
│           └── SettingsView.java  # 设置
└── build.sh                       # 无 Gradle 构建脚本
```

## 核心实现思路

**模型调用链**：所有模型组成有序列表，`第一个 ready 的模型` 即当前使用项；请求失败（HTTP 错误、SSE 中断、超时）自动尝试下一个，并在界面上标注切换原因。三种协议各有独立的请求构造器和 SSE 解析器，推理模型（如 DeepSeek V4.1）的 `reasoning_content` 单独处理并兜底。

**微信 ClawBot 接入**：实现的是微信官方 iLink 开放接口（与 `@tencent-weixin/openclaw-weixin` 插件同源）：

```
POST /ilink/bot/get_bot_qrcode?bot_type=3      → 获取登录二维码
GET  /ilink/bot/get_qrcode_status?qrcode=…     → 长轮询扫码状态（wait/scaned/need_verifycode/confirmed）
POST /ilink/bot/getupdates                     → 长轮询收消息（Bearer bot_token，带 get_updates_buf 断点续传）
POST /ilink/bot/sendmessage                    → 回复消息（超长自动分段）
```

收到的图片消息通过 CDN 下载并 **AES-128-ECB** 解密（密钥两种编码均兼容），解密后作为多模态输入送给模型。凭据（bot_token）保存在本机，进程重启后从持久化的游标继续拉取。

**轻量化**：单 Activity + 纯 Java + 代码绘制 UI；ZXing 经 R8 裁剪；资源只有 4 个小文件；权限仅 `INTERNET`、`ACCESS_NETWORK_STATE`、前台服务与通知相关；无常驻推送 SDK、无第三方统计。

## 构建与打包

不依赖 Gradle / Android Studio。需要 JDK 17 和 Android SDK（build-tools 34 + platform 34），路径在 `build.sh` 顶部可改：

```bash
bash build.sh
```

流程：`aapt2 compile` → `aapt2 link` → `javac` → `R8`（裁剪 + 混淆）→ 打包 `classes.dex` → `zipalign` → `apksigner` 签名。首次运行会自动生成签名证书 `keystore/openclaw.jks`（口令 `openclaw2024`，**请勿提交你生成的证书**）。

产物：`build/OpenClaw.apk`

## 隐私与安全

- 所有 API Key、模型凭据只保存在本机 SharedPreferences，不上传任何服务器
- 仓库源码与发布包**不包含任何密钥**，运行时需自行填写
- 不注入微信、不读取通知、不申请无障碍权限

## License

MIT
