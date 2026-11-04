# 项目协作约定

## 架构文档维护

- 修改 mairy-chat 前先阅读 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，再核对受影响的代码和专项 NOTES。
- 凡影响结构 / API / 功能行为的代码改动，必须在同一次提交（或同一 PR）里同步更新 `docs/ARCHITECTURE.md`，完成后再结束任务。
- 文档使用简洁中文，只描述当前实现；保留现有 NOTES，不把待办或计划写成已实现功能。

## 修改与交付

- 写 Markdown 文件不用 Node，使用内置文件编辑工具；检索和替换代码优先用 Node。
- 前后端按项目既有风格修改，优先复用现有功能；不要修改前端公共组件，不要在循环中调用 SQL。
- 修改代码添加简要中文注释；新增或修改方法时，在方法上说明其简要作用。
- 每次修改完成后 review，显示创建 / 修改的文件及内容；发现不合适之处先告知维护者，由维护者决定修改范围。
- 不编译前端，不访问浏览器。提交、推送和部署遵循当轮用户授权；不提交真实 `.env`、密码或私钥。

<!-- BEGIN ui-craft (managed — do not edit) -->
# ui-craft skill

The ui-craft skill is installed at: C:\Users\mrmai\.codex\skills\ui-craft

Load it at the start of any UI design or implementation task.
<!-- END ui-craft -->
