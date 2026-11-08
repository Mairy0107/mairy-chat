# AI 通用助手 P0

## 使用与升级

1. 已有账号版数据库手动执行 `backend/src/main/resources/schema-upgrade-ai.sql`；新库使用 `schema.sql`。不会自动连接或迁移远程数据库。
2. 服务端环境设置 `DEEPSEEK_API_KEY`，可选 `DEEPSEEK_BASE_URL=https://api.deepseek.com`、`DEEPSEEK_MODEL=deepseek-chat`。仅服务端使用，不放入 VITE_，不要提交真实配置。缺少密钥返回「助手未配置」，大厅入口仍显示。
3. 按既有方式启动后端 `mvn spring-boot:run`，前端 `npm.cmd run dev`。登录后点击大厅「助手」，或打开 `http://localhost:5173/assistant`；生产路径 `/chat/assistant`。

助手对话在服务端和 MySQL **明文保存**，当前会话上下文发送至云端模型。它不属于房间 E2EE，也无法读取房间、私信或用户档案。界面提示不要发送密码、密钥和隐私。P0 只做文字助手，P1 问卷 / 人格覆盖仅以 `profileCompleted:false` 状态占位。

## 配额与过滤

- `application.yml` 的 `chat.ai.daily-quota` 默认40；按账号与上海自然日持久化，条件更新确保并发不超额。通过校验并被接受的请求计一次；上游失败或停止不退额。未配置、越权、输入拦截、会话忙碌和超额不调用模型。
- `sensitive-words.txt` 与 `chat.ai.sensitive-words` 合并，每行一个字面词，忽略空行 / 井号注释及英文大小写。输入先检查；输出累计检查并保留词长尾部，命中立即停止、替换为统一拒绝，只保存拒绝正文。审计日志仅有事件类型。
- 同会话3分钟租约防止并发生成，最多8条并行模型流；数据库事务不跨越网络等待。上下文最近20条、总计24000字符，输入2000字符，输出16000字符 / 2048 tokens。
- SSE 使用 `user / delta / replace / done / error`；收到 done 才确认保存成功。停止按钮中止 fetch，服务端下一次写入发现断连后收尾，可能短暂显示「正在回复」。刷新可确认实际记录；进程中止可能仅保留用户消息。
- 上游使用 4096 字符缓冲分块解码 UTF-8，完整 SSE 帧立即交付，保留单行及累计 data 的 65536 字符限制；下游每事件 flush。nginx `/chat/api/` 显式设置 `proxy_buffering off`；前端将 delta 加入打字机队列，约每 30ms 展示一个 Unicode 码点；done 后仍等待队列展示完毕，replace 清除旧队列。实现记录见 [VOICE_STREAM_NOTES.md](VOICE_STREAM_NOTES.md)。

## P0 验收

1. 未登录访问 AI API 返回401，页面进入登录；账号 A 创建会话后，账号 B 不能读取或发送（403），会话列表不串号。
2. 发送文字后增量追加，完成后刷新仍能读到双方消息；超过30条使用「更早消息」。读旧消息时不强制滚到底部。
3. 未配置返回503「助手未配置」；达到当日次数返回429。服务重启后计数保留，上海午夜按新日期重新计数。
4. 输入命中不调用上游、不占次数；模型跨分片输出命中后替换为拒绝，库内没有被拦截输出。错误不回显命中词、上游正文或异常详情。
5. 流中断显示中文错误，可刷新；停止生成后保留用户消息，不把未确认的临时文本当作已保存回复。
6. 大厅、好友、DM、房间 E2EE 和管理员流程沿用原入口。无浏览器自动化验收；实际 MySQL、真实 DeepSeek 及代理 SSE 需在维护者环境验证。

## 已知边界与 review 待决定项

- 基础词表只能匹配字面文本，不能防止插入空格、同音字等绕过，也不保证模型不会输出用户不应提交的秘密。系统提示不替代内容审核。
- 原生同步 MVC 流占用一个请求线程，P0上限8条适合低并发；扩容、共享全局并发控制、语义审核、删除 / 保留周期和token计费尚未实现。
- 会话列表显示最近100个；消息按页加载，未做长会话虚拟化。浏览器断连到服务端识别可能有延迟；进程中止后不自动补全回复。
- 不包含问卷 UI/API、人格档案、语音、附件、工具调用或自动重试。维护者可按实际流量决定后续范围。

## 修改内容

新增后端 `AiController`、AI DTO / 实体 / Mapper、`AiProperties` 与 `service/ai/`（业务短事务、SSE、薄 RestClient、过滤器）；新增三张 AI 表的升级脚本并同步完整 schema；新增资源词表和配置项。前端新增 `api/ai.js`、`ai-stream.js`、`AssistantView.vue`，修改 router 与大厅入口；公共组件、房间、DM 和 mairy-website 未修改。同步架构文档、环境示例及专项测试。

## 本次验证

验证结果在完成测试后补充；本节不代表真实 DeepSeek、MySQL 或浏览器联调已完成。


## 2026-11-08 Spring AI 迁移工作区记录（未完成）

本轮保留已有 POM 的 Spring AI 1.1.8 BOM / DeepSeek / Reactor Netty 改动，并补入 `spring-webflux` 客户端模块。
新增 `AiModelGateway`、手动构造官方 `DeepSeekChatModel.stream(Prompt)` 的 `SpringAiChatGateway` 和请求独享的
`UpstreamSseGuard`；业务流改注入接口，集成测试替身同步改为接口。`legacy` 实现保留，配置共用 `chat.ai.*`，
不引入 `spring.ai` 凭证或无效占位密钥，也不引入自动配置 starter。provider 默认 `spring-ai`，可显式设置 `legacy`。
无密钥沿用 prepare 前置 503 门禁；状态及历史可用的真实启动验收尚未执行。

默认地址、模型及参数为 `https://api.deepseek.com/chat/completions`、`deepseek-chat`、温度 0.3、max_tokens 2048，
连接 / 读取 / 总时限为 10 / 45 / 120 秒。新增参数范围与完整限制见架构文档迁移状态节。
原始流检查实际 DONE，限制单行及 data 为 65536 字符，并限制整轮协议体和帧数；官方 SDK 仍负责消息映射。
MVC 在请求线程通过有界 Stream 消费，共享连接池 / 线程在关闭时释放；关闭工具执行、自动重试和传输自动重放。
STOP / LENGTH 与 DONE、非空正文共同用于完成判断；content_filter 对应拒绝，其他异常沿用安全失败提示。
停止 / 失败仍扣额，原有短事务、SSE 事件、文本过滤与旧 requestId 不能释放新租约的约束保留。

取消限制：下一次写入或 checkError 才能识别浏览器断连，静默上游受读取及总时限约束；
定时器可取消上游，不能强行打断阻塞中的 Servlet 写入。未进行真实慢写 / 断连实验，不能宣称立即取消。

### 实际执行结果

在 `backend/` 使用 `D:\Environment\apache-maven-3.9.15\bin\mvn.cmd`，指定 `-Dmaven.repo.local=D:/maven_repository`：

- `dependency:tree`：失败，环境拒绝访问 Maven 镜像，dependency 插件 3.8.1 未解析完成。
- `-o -DskipTests compile`：失败，资源插件缺少可离线解析的 plexus-interpolation 1.26、maven-filtering 3.3.1、
  javax.inject 1、slf4j-api 1.7.36、plexus-build-api 0.0.7、commons-io 2.11.0；未进入 Java 编译。
- `git diff --check`：通过。

权限阻塞后按用户要求停止代码实现和测试，未申请新权限、修改 Maven / 系统配置或操作缓存现有包。
没有完成新增网关 / guard / provider 测试，也未执行 legacy、H2 集成、目标测试、完整 Maven 测试或前端 Node 测试；
本轮测试通过 / 失败数均无可报告数据。没有浏览器、真实模型、真实数据库或线上服务调用。

### Review 状态

静态复核修正了本轮新增中文注释的管道编码损坏、遗留回调类型遮蔽及 YAML OFF 字面值。
由于 Java 编译和行为测试尚未运行，SDK 实际映射、日志隔离、provider 非法配置启动失败、真实无密钥启动、
首增量时机、DONE / EOF 边界、超时、过滤取消、断连释放、保存失败与旧租约等均不能判为通过。
legacy 原有总时限只在读取返回后检查，亦未进行绝对超时强化或验证。当前保留为未完成工作区，不能标记 local-ready-review。

## 2026-11-08 本次续作验证记录（仍未完成）

本次保留原有 8 个已修改文件、3 个新增迁移文件及全部未跟踪临时文件，没有提交、推送、部署或调用真实模型 / 数据库。
新增四个测试文件：`AiNoKeyStartupTest`、`AiProviderTest`、`SpringAiChatGatewayTest`、`UpstreamSseGuardTest`。
这些测试针对真实应用无密钥启动、provider 单实例和配置校验、官方模型序列化、本地 TCP 超时 / 取消及原始 SSE 边界；均尚未执行到测试方法，不能作为通过证据。

在 `backend/` 使用 `D:\Environment\apache-maven-3.9.15\bin\mvn.cmd`，所有 Maven 验证命令均指定 `-Dmaven.repo.local=D:/maven_repository`：

- `dependency:tree`：成功。实际解析 Spring AI 1.1.8、WebFlux 6.2.19、Reactor Netty 1.2.18；Boot 3.5.16 和 MyBatis-Plus 3.5.18 保持不变。
- `-DskipTests compile`：成功，编译 84 个生产源文件。
- `-Dtest=AiClientTest,AiIntegrationTest,UpstreamSseGuardTest,SpringAiChatGatewayTest test`：testCompile 失败，找不到项目生产类型；未运行测试方法。
- 加入 `AiProviderTest,AiNoKeyStartupTest` 的定向测试同样在 testCompile 失败。
- 以 `-Dmaven.compiler.useIncrementalCompilation=false`、`-Dmaven.compiler.fork=true` 分别定位仍失败；没有修改 POM 插件或升级技术栈。
- `-Dmaven.compiler.verbose=true` 仅作编译诊断；没有使用 Maven -X / debug。项目测试 classpath 的只读检查确认包含生产输出目录，`javap` 能读取已生成的 AiProperties。
- `--version` 自检明确输出 `Access is denied.`，同时打印 Maven 3.9.15 / Java 17.0.20。拒绝来源未确定；按本轮用户要求停止后续构建与测试，没有改权限、配置或绕路调用。
- `git diff --check`：成功（仅 CRLF 提示）。

测试实际执行数为 0；不是测试通过。完整 `mvn test` 与 `node --test frontend/src/api/ai-stream.test.mjs` 未执行。
没有读取 Maven settings、环境文件、认证文件或修改镜像 / 代理 / 公司仓库，没有清空依赖缓存。

静态 review 待验证项：guard 的普通协议异常目前可能以 RuntimeException 穿过网关；JSON 尾随内容是否被拒绝尚未验证；新测试本身尚未通过 testCompile。
保存失败、旧租约、新增第九条流门禁及真实业务过滤与网关取消的组合测试仍未补齐。不能标记 local-ready-review；保留未完成工作区供后续处理。

## 2026-11-08 本轮只读诊断结果（访问拒绝，停止测试）

本轮没有修改生产代码或测试代码；保留已有迁移文件及全部原有临时文件。未读取 Maven settings、认证、凭据或环境秘密文件，未修改启动器、仓库、代理、安全配置或 ACL，也未调用真实模型、数据库、服务及远程 Git。

### testCompile 的已确认根因

- 使用原 Maven 入口和正常配置，在 `backend/` 执行三次窄范围测试编译诊断，均带 `-Dmaven.repo.local=D:/maven_repository -Dtest=UpstreamSseGuardTest`：一次附加 `-Dmaven.compiler.verbose=true`，一次附加 `-Dmaven.compiler.fork=true -Dmaven.compiler.debugFileName=ai-test-javac`，最后一次附加 `-Dmaven.compiler.verbose=true`。三次均 BUILD FAILURE，停在 testCompile；测试执行数 0。
- javac verbose 的实际 classpath 包含 `backend/target/classes`；没有 Java module 描述文件。Java 普通目录枚举、显式读取 AiProperties.class 和 URLClassLoader 加载该类均成功。
- 仓库内临时探针调用 JDK 标准文件管理器列举 `com.mairy.chat.config` 得到空结果；最小编译同样报包不存在。
- 对照本机 JDK 17.0.20 的 `src.zip`：`JavacFileManager.DirectoryContainer.list` 调用 `caseMapCheck`；后者调用 `Path.toRealPath(LinkOption.NOFOLLOW_LINKS)`，遇到 IOException 返回 false，目录被忽略。
- 对同一目录直接检查该路径元数据操作，得到 `java.nio.file.AccessDeniedException: C:\Users\mrmai\Desktop\mairy-chat\backend\target\classes\com\mairy\chat\config`。这解释了生产 class 存在、classpath 正确但 javac 忽略生产包的现象。拒绝来自具体路径规范化操作；现有证据不能区分 ACL 与托管沙箱的底层原因。

按本轮授权边界，发现实际访问拒绝后停止所有构建和测试，不更改权限，不通过搬移目录、打包生产类或另一调用入口绕过该操作。继续验证需要原运行环境允许 javac 对本仓库生产输出包目录执行上述真实路径元数据查询；本轮未申请或实施该权限变化。临时 Java 探针只检查非秘密路径，已清理。

### Maven 启动字符串与交付状态

已只读核对 mvn.cmd。四个用户 Maven RC 文件不存在；逐级 cd、普通 NUL 重定向和 JDK 版本探测分别正常；未发现 MAVEN_OPTS、MAVEN_ARGS、MAVEN_DEBUG_OPTS 覆盖。正常 Maven 调用仍输出 `Access is denied.`，但该启动字符串的精确来源尚未证明，不将其与上述 javac 拒绝混为同一事件；未启用全局 -X 或输出环境 / 配置。

本轮 targeted 测试编译命令 3 次失败，实际用例执行 0；完整 `mvn test` 与 `node --test frontend/src/api/ai-stream.test.mjs` 未执行。此前 dependency:tree 与生产 compile 的成功记录保留。JSON 尾随内容、安全异常分类及新增组合覆盖仍未修复或验证；当前不能标记 local-ready-review，没有提交、推送、PR 或部署。
