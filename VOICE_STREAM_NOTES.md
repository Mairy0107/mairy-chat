# 房间加密语音与助手流式记录

核对日期：2026-11-04。仅实现房间语音，范围遵循 CANONICAL_SCOPE.md。

## 助手流式链路

- 保留 AssistantView 打字机：收到 delta 后排队，约每 30ms 展示一个 Unicode 码点；done 后继续逐字显示剩余正文，replace 清空旧队列，停止和卸载清理定时器。界面隐私提示使用「云端模型」。
- 后端 DeepSeekClient 保留 4096 字符缓冲读取及 UTF-8 增量解码，完整 SSE 帧交付后逐事件 flush；保留 65536 字符行 / data 限制、超时和 DONE 校验。
- nginx API 代理保留 `proxy_buffering off`，响应保留 `X-Accel-Buffering: no`。网络转发效率和前端逐字展示是两层行为。
- 修正 FEATURE_AI_NOTES.md 和 docs/ARCHITECTURE.md 的“直接追加 / 无打字机延迟”描述。保留原有测试，只修正一条 done 正文修正用例的异步等待竞态，由测试主体推进假时钟，未改变断言和产品行为。

## 录音、发送与播放

入口仅在 ChatRoomView，沿用房间现有密钥解锁、指纹核验、成员授权、REST 发信和 WebSocket 推送。

1. HTTPS / localhost 且浏览器支持 MediaRecorder 和 getUserMedia；普通成员密钥就绪后显式点击「录语音」请求麦克风。
2. 选择浏览器可录制的 WebM / Ogg Opus / MP4，目标 32 kbps；60 秒定时停止，最多 512 KiB。空录音、录音失败、超限和权限拒绝显示简短中文提示。
3. 「结束录音」立即释放麦克风，录音保留在当前页面内存，提供原生播放器本地预览；不自动上传。「取消语音」丢弃草稿。
4. 点击「发送语音」后先加密，成功后清除草稿；发送失败保留草稿供重试。录音或预览期间禁发文字，文字草稿保留。
5. 历史与推送只有解密校验通过的语音才显示播放器，默认不预加载、不自动播放；不支持的格式提示无法播放。
6. 取消、锁定、会话失效和离开房间清理录音；版本号拒绝迟到的权限、录音、发送和解密结果。刷新历史、替换消息、锁定和卸载撤销对应 Blob URL。跨标签页登录变化同步清理。

没有修改 DmChatView，没有 DM E2EE；AssistantView 没有语音入口。没有修改前端公共组件。

## 密文契约

- 房间 POST / 历史 / WS 消息增加 `kind`：`text` 或 `voice`；省略时默认文字。旧记录默认为文字，原文字 AAD 不变。
- 语音明文载荷为 `{version:1,mime,duration,data}` 的 UTF-8 JSON，duration 为 1～60000 整数毫秒，data 为不超过 512 KiB 音频的规范 Base64；格式、时长和数据一起加密。
- 使用现有房间 AES-256-GCM 密钥、随机 12 字节 IV、128 位标签。语音 AAD 为 `mairy:e2ee:v1:message:<roomId>:member:<memberId>:kind:voice`；类型、房间或发送会话被替换后不能校验通过。
- 外层仍只提交 `{kind:'voice',enc:1,iv,content}`。后端校验成员、规范 Base64、IV、类型及密文大小，拒绝明文、未知类型和重复 IV；不持有房间密钥。文字密文维持 17～4016 字节；语音密文 17～700016 字节，Base64 最长 933356 字符。
- 解密端再次校验格式、大小、时长及版本，并剔除网络注入的 voice / voiceUrl 等展示字段；只有本地校验通过的字节可生成播放 URL。失败仅显示占位，无明文回退。
- Base64 分块编码避免大音频展开参数超出调用栈。房间 WS 慢连接缓冲从 64 KiB 调至 1 MiB，保留 5 秒发送限制，可容纳单条语音密文。
- 服务端可见消息类型、长度和既有元数据；沿用房间共享密钥的现有安全边界，不增加前向保密或发送者签名。

## 数据库升级

新库使用 `backend/src/main/resources/schema.sql`。已有房间 E2EE 库先备份，再手动执行一次 `backend/src/main/resources/schema-upgrade-voice.sql`：

- 仅将 chat_message.content 扩为 MEDIUMTEXT，新增 kind VARCHAR(8) NOT NULL DEFAULT 'text'。
- 保留历史正文、enc、iv 和唯一索引，不修改 DM、助手或身份 / 信封表。
- 应用不自动迁移；先升级数据库，再启用本次代码。未连接或修改真实 MySQL。

## 修改文件

| 文件 | 内容 |
| --- | --- |
| frontend/src/views/ChatRoomView.vue | 录音、预览、密文发送、本地解密播放、失败提示及资源清理 |
| frontend/src/crypto/e2ee.js | 语音加密载荷、类型AAD、解密校验与分块Base64 |
| backend/.../dto/ChatRequests.java、ChatViews.java；entity/Message.java | 房间消息kind与请求长度 |
| backend/.../service/MessageService.java | 类型白名单、分类型密文大小及保存 / 响应 |
| backend/.../websocket/ChatSocketHandler.java | 房间慢连接缓冲容纳单条语音 |
| backend/src/main/resources/schema.sql、schema-upgrade-voice.sql | 新库结构与一次性升级 |
| frontend/src/crypto/voice.test.mjs、views/ChatRoomView.test.mjs | 真Web Crypto大音频、篡改、格式 / 大小 / 时长、录制 / 预览 / 密文发信、权限和清理 |
| backend/.../AuthIntegrationTest.java、TimezoneRegressionTest.java | 语音持久化 / 历史 / WS、权限、IV、长度、类型及DTO兼容 |
| FEATURE_AI_NOTES.md、docs/ARCHITECTURE.md、本文 | 当前行为、迁移与验收说明 |
| 已有 AssistantView.vue / 测试、DeepSeekClient / AiClientTest、deploy/nginx-chat.conf.snippet | 保留工作树中既有的打字机、隐私文案、SSE与代理改动；助手测试仅补充同步修正 |

表中的 backend/... 均指 backend/src/main/java/com/mairy/chat 或 backend/src/test/java/com/mairy/chat 对应目录。

## 本次验证

- `mvn -q test`：22 项通过，无失败或跳过；使用隔离 H2，未读取真实数据库配置。首次检查遇到旧 target 类签名，清理后补齐测试 DTO 参数，再次测试通过。
- `mvn -q -DskipTests package`：成功。
- `node --experimental-vm-modules --test src/views/AssistantView.test.mjs src/views/ChatRoomView.test.mjs src/views/DmChatView.test.mjs src/crypto/e2ee.test.mjs src/crypto/voice.test.mjs src/api/ai-stream.test.mjs`（frontend）：26 项通过。
- `npm.cmd run build`：成功；仅按本轮要求尝试构建，dist 不纳入提交。
- `git diff --check`：通过。
- 未访问浏览器，未进行真实麦克风 / 跨浏览器编解码、MySQL ALTER、真实模型和线上代理联调。

## Review 与维护者待决定项

Review 未发现阻断问题。测试验证了加密通道、无明文回退以及原有助手打字机 / DM 边界。

语音随历史整页加载，极端情况下每页 50 条接近上限的语音约有 47 MB 密文响应，并产生解密内存开销；本次沿用既有分页，未增加对象存储或媒体按需下载。是否后续拆分媒体加载由维护者决定。不同浏览器录制格式和播放能力仍需真实设备验收；不能将本地测试等同于浏览器或线上部署验收。

任务草稿、dist、tgz、真实 .env、密码和私钥均不纳入提交；本次不推送、不部署。
