# LOGIN_MOTION_TASK — 「聊」字入场动画 + 登录首页视觉刷新

工作目录：`C:\Users\mrmai\Desktop\mairy-chat`

参考截图（务必打开查看）：`docs/login-ref.png`
- 红色箭头指向左侧品牌区中央圆形「聊」徽章（深色圆角方块 + 白字「聊」+ 细发光圆环 + 左上金色四角星）。
- **该徽章是入场动画的核心视觉锚点。**

## 硬性约束（违反即失败）

1. **禁止** `git commit` / `git push` / 任何部署到 ECS / SSH / 修改云端 nginx。
2. **禁止** 改坏现有认证流程（登录 / 注册 / 忘记密码）与路由跳转逻辑；脚本 setup 里的 `submit` / `saveLogin` / `machineFingerprint` 行为保持可用。
3. 主要改前端：`frontend/src/views/AuthView.vue`，必要时可抽小组件到 `frontend/src/components/`，或微调全局样式。**不要**为了动效大改后端。
4. 完成后必须在 `frontend/` 执行 `npm.cmd run build` 成功。
5. 完成后写根目录短说明 `LOGIN_MOTION_NOTES.md`（中文）：改了什么、如何本地预览、入场动画如何触发/跳过。

## 需求

### 1. 入场动画（核心）

- 以「聊」徽章为中心：缩放 / 淡入 / 柔和光晕 / 金色星点轻轨（tasteful，1–2 秒，不要花哨）。
- 可每会话播放一次（`sessionStorage`），结束后自然落位到登录卡左侧品牌区。
- 尊重 `prefers-reduced-motion: reduce`：直接显示最终态，不播动画。
- 移动端也要有简化版（当前小屏隐藏了 orbit——请让徽章在小屏仍可见，或做紧凑版入场）。

### 2. 登录首页视觉刷新

- 保持现有品牌调性：左侧深蓝/青绿高端感，右侧白表单。
- 加**简单 CSS/SVG 插画背景**（柔和几何、聊天气泡剪影、轻渐变光斑即可；不要依赖大体积位图；可用小型 inline SVG）。
- 待机微动效：背景缓慢漂浮 / 渐变微移、徽章呼吸光、按钮 hover 过渡。
- 整体要「高级、克制」，对齐参考图气质，不要赛博霓虹堆特效。

### 3. 验收

- `npm.cmd run build` 通过。
- 打开 `/login`：先见「聊」入场，再看到刷新后的首页；登录表单仍可提交。
- 未 commit / 未 push / 未部署。

请直接改代码并本地 build 验证，最后用中文简要汇报改动文件列表。
