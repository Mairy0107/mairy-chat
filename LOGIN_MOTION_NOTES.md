# 登录首页动效说明

- 修改 `frontend/src/views/AuthView.vue`：保留深蓝青绿品牌区与白色表单，以「聊」徽章完成缩放淡入、柔和圆环和金色星点轻轨；桌面入场约 1.6 秒，小屏约 1.2 秒。
- 新增小型内联 SVG 聊天气泡、几何圆弧、缓慢漂浮光斑、徽章呼吸光及按钮交互过渡。所有样式限制在当前页面，小屏保留紧凑徽章；未修改公共组件、后端或路由。
- 原有 `submit`、认证表单、`saveLogin`、`machineFingerprint` 调用及跳转逻辑保持原样；新增方法已附中文说明，离开页面时清理动画计时。

## 本地预览

在 `frontend/` 执行 `npm.cmd run dev`，手动打开 `http://localhost:5173/login`。需处于未登录状态；真实提交需要本地后端运行。生产构建的预览可执行 `npm.cmd run preview`，地址为 `http://localhost:5173/chat/login`。

## 触发与跳过

- 每个标签页会话首次进入 `/login` 时播放，使用 `sessionStorage` 的 `mairy.auth.entrance.seen` 标记去重；刷新、注册后返回及后续返回登录页均不重复播放。
- `/register` 和 `/forgot-password` 直接显示最终态，不消耗首次登录入场机会。
- 系统开启“减少动态效果”（`prefers-reduced-motion: reduce`）时跳过全部入场、待机动画及交互位移；存储不可用时也直接显示最终态。
- 如需重播，可在开发者控制台执行 `sessionStorage.removeItem('mairy.auth.entrance.seen')` 后刷新 `/login`，并关闭系统“减少动态效果”。入场期间表单没有遮挡层，仍可操作。

## 验证与复查

- 已在 `frontend/` 执行 `npm.cmd run build`，构建成功；`git diff --check` 通过。
- 通过内存脚本检查首次播放、会话去重、减少动态、禁用存储、卸载清理及路由切换；模拟登录、注册、忘记密码提交通过，并对比确认原有提交方法和认证表单未变。
- 复查未发现需要额外修改的问题。按要求未访问浏览器，实际画面、移动端触感及真实后端联调仍需手动验收，构建与模拟检查不代替这些验收。
- 未执行 git commit、git push、SSH、ECS 部署或云端 nginx 修改。
