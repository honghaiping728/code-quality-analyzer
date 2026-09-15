# 基于大语言模型的智能代码质量分析与自动修复建议系统

结合 AST 与 LLM，对 Git 仓库代码进行 Bug、安全、性能、规范四维分析，输出问题成因与修复建议。

## 模块
- cq-repo：Git 仓库接入
- cq-scan：扫描调度
- cq-parser：AST 解析
- cq-rule：规则引擎
- cq-agent：Code Agent
- cq-suggestion：修复建议
- cq-report：报告与趋势
- cq-admin：规则与权限管理
- cq-web：启动模块
