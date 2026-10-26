# 贡献指南

`main` 是受保护的主分支。所有开发在工作分支进行，代码、文档、版本号及发布准备变更都通过 Pull Request 合并到 `main`，仓库管理员同样遵守。

## 开发与合并

1. 修改文件前，检查工作区；保留他人的未提交变更。从最新的 `origin/main` 创建工作分支，例如 `feat/short-topic`、`fix/short-topic` 或 `docs/short-topic`。Agent 默认使用 `codex/` 前缀。

   ```sh
   git status --short --branch
   git fetch origin
   git switch -c feat/short-topic origin/main
   ```

2. 在工作分支开发、检查和提交，再推送该分支。

   ```sh
   git diff --check
   git add <本次变更的文件>
   git commit -m "feat: describe the change"
   git push -u origin HEAD
   ```

3. 创建以 `main` 为目标的 PR，说明变更目的、验证结果和已知限制。按改动范围运行 [README 中的检查](README.md#开发)；纯文档改动无需运行应用测试。审批人数和必需检查以 GitHub 当前分支规则为准。

   ```sh
   gh pr create --base main
   ```

4. 在 GitHub 上通过 PR 合并。不要在本地将变更合入 `main` 后直接推送；处理冲突时更新工作分支，再继续原 PR。

## 主分支约束

- 不在 `main` 上开发或创建提交，不直接推送到远端 `main`。
- 不强制推送、改写远端 `main` 的历史或删除远端 `main`；`--force-with-lease` 也属于禁止范围。
- 不通过关闭或削弱分支保护、管理员绕过等方式跳过 PR 流程。
- 发布准备变更也走 PR；从 `main` 上已合并的提交构建正式版本并创建标签，详见 [发布说明](docs/RELEASING.md)。
