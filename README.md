# 小夜 QQBot

安卓端 QQ 机器人 —— **llama.cpp 内置进 APK**，手机本地跑 `.gguf` 完全离线：
不依赖 Termux、不依赖电脑、断网照常推理。

界面用 **Kotlin + Jetpack Compose + Material Design 3**，
云端侧支持 DeepSeek / Kimi / 智谱 / 硅基流动 / OpenAI / Ollama / llama.cpp server / 腾讯混元。

🌐 **官网**：`docs/index.html`（Material Design 3，可直接用 GitHub Pages 托管）

---

## 核心：llama.cpp 是怎么进去的

```
app/src/main/cpp/ggml_jni.cpp   ← JNI 桥（模型加载 / prefill / 采样循环）
        │
        ├─ CMakeLists.txt 把 third_party/llama.cpp 一起 add_subdirectory
        │
        └─ 编出【单个】libggml-jni.so，打进 APK 的 arm64-v8a
                ▲
                │
        Kotlin: LlmEngine.kt 通过 external 方法调用
```

**采样链自己实现**，没走 llama.cpp 自带的 sampler：

```
logits → 重复惩罚 → temperature → softmax → top-k → top-p → 多项式采样
```

另外实现了 ChatML 模板拼装和停止词截断。停止词有个坑：token 是分片过来的，
`<|im_end|>` 可能被切成好几段，所以 Kotlin 侧用「缓冲 + 最长前缀保留」——
结尾看着像停止词开头的片段先扣住不发，确认不是再吐出去。

多轮对话走**增量 prefill**：第一轮带 system + user，之后每轮只补
「上一轮回复 + 新问题」，历史留在 KV cache 里不重算，比每轮重发整个对话快得多。

## 模型怎么选

| 模型 | 体积 | 内存 | 说明 |
|---|---|---|---|
| **Qwen2.5-1.5B-Instruct-Q4_K_M** | 约 1 GB | 4 GB+ | **默认推荐**，中文好，手机跑得动 |
| Qwen2.5-0.5B-Instruct-Q4_K_M | 约 0.4 GB | 3 GB+ | 内存吃紧就换这个 |
| Qwen2.5-3B / Llama-3.2-3B Q4_K_M | 约 2 GB | 8 GB+ | 能跑，但明显慢 |
| 7B 及以上 | 4 GB+ | 12 GB+ | 能加载但慢到没法聊，**不推荐** |

手机没有独立显存也没有风扇，连续推理几分钟会发热降频 —— 这是正常的。
量化统一选 `Q4_K_M`，体积和质量的甜点。

## QQ 接入：三种模式

| 模式 | 说明 | 备注 |
|---|---|---|
| **QQ 官方 API** | 在 q.qq.com 创建机器人，填 AppID + 密钥 | 合规稳定，长期运行首选 |
| **OneBot 反向 WS** | 协议端开反向 WebSocket，App 主动连出去 | **推荐**，不用暴露端口 |
| **OneBot HTTP 上报** | 协议端把消息 POST 到手机监听端口 | 需同一局域网，两个地址都要填 |

### 消息规则与限流

一条消息要过四层才轮到推理：

```
去重（同一 message_id 窗口内只处理一次）
  → 群白名单（按群号放行，留空=全部）
  → 冷却（同一群/人两次回复最小间隔）
  → 触发规则（仅@ / 前缀 / 关键词 / 全回）
```

回复会按字数**分段发送**，段间留间隔模拟真人打字，
配上限流能明显降低被风控的概率。

### ⚠️ 关于封号风险

**OneBot 属于第三方协议，腾讯并未授权，使用存在封号风险。**

强烈建议**用小号测试**，不要拿常用的大号冒险。
长期稳定运行请走 QQ 官方 API。

## 密钥安全

API Key / Token 走 **Android Keystore** 的 AES-256-GCM 加密后落盘：

- 密钥在 Keystore 里生成，只存句柄，拿不到原料
- 每次加密生成随机 12 字节 IV，密文格式 `[IV][密文+Tag]`
- 明文不写入存储；旧版本若存过明文，读取时自动升级为密文

## 编译

### 让 GitHub 云端帮你编（推荐）

仓库已配好 GitHub Actions，push 到 main 即自动编译，
产物发到 **Releases** 的 `native-latest` 标签，直接下载 `app-debug.apk`。

### 本地编译

```bash
git clone https://github.com/excuse-2580/xiaoye-qqbot
cd xiaoye-qqbot
./scripts/fetch-llama-cpp.sh      # 拉 llama.cpp 源码
# Android Studio 打开，或：
./gradlew assembleDebug
```

需要 Android Studio Ladybug/Koala+、JDK 17、NDK。
只出 `arm64-v8a`，minSdk 26。

> llama.cpp 上游 API 改得很勤，CI 里**锁定了已验证可编译的 commit**
> （`2b129ccfa03aea330d2d9ac4650a10de393dbe3a`）。要升级请一并验证 JNI 桥。

## 怎么用

1. **装 APK**（Android 8.0+，arm64）
2. **下模型**：Qwen2.5-1.5B-Instruct-Q4_K_M（约 1 GB），传到手机
   （HuggingFace 打不开就把 `huggingface.co` 换成 `hf-mirror.com`）
3. **加载**：App →「模型」→ 勾选「用手机本地 GGUF」→ 选 `.gguf` → 点「加载」
4. **聊天**：状态变「已就绪」后回「对话」页，断网也能聊
5. **接 QQ**（可选）：「机器人」页选模式、填参数、设触发规则 → 点「启动」

机器人以前台服务常驻，锁屏也不断。若被系统杀掉，
把 App 的电池策略设为「无限制」。

## 目录结构

```
app/src/main/
├── cpp/
│   ├── ggml_jni.cpp              JNI 桥 + 自写采样循环
│   └── CMakeLists.txt            链 llama.cpp → 单个 libggml-jni.so
├── java/com/xiaoye/qqbot/
│   ├── MainActivity.kt
│   ├── data/                     模型源 / 智能体 / QQ 配置 / Prefs
│   ├── engine/
│   │   ├── LlmEngine.kt          本地引擎（ChatML + 停止词 + 增量 prefill）
│   │   ├── CloudEngine.kt        云端 SSE 流式
│   │   └── HunyuanSigner.kt      TC3-HMAC-SHA256 签名
│   ├── qq/
│   │   ├── BotService.kt         常驻前台服务，串起整条链路
│   │   ├── BotRules.kt           去重 / 白名单 / 冷却 / 触发 / 分段
│   │   ├── OneBotProtocol.kt     报文解析 + 签名校验（两种模式共用）
│   │   ├── OneBotWs.kt           反向 WebSocket
│   │   ├── OneBotHttp.kt         HTTP 上报（自带极简 HTTP 服务）
│   │   └── QQOfficial.kt         官方 API（鉴权 + WSS + 心跳）
│   ├── security/KeystoreCrypto.kt Android Keystore AES-256-GCM
│   ├── vm/AppViewModel.kt
│   └── ui/                       Compose 页面（对话/智能体/模型/机器人/设置）
└── res/
docs/                             官网（Material Design 3）
scripts/fetch-llama-cpp.sh        拉 llama.cpp 源码
third_party/llama.cpp             ← 跑 fetch 脚本后才有
```

## 已知限制

- 只支持 **arm64 安卓 8.0+**，内存建议 6 GB 起
- 手机适合短交互，不适合长文生成
- GPU 加速默认关闭（纯 CPU 最稳）
- 云端对话每轮全量重发历史（云端无状态），本地模型才是增量的

## 许可

llama.cpp 部分遵循其上游许可；其余代码 MIT。
