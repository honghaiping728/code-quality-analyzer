# 更新日志

记录每个对外版本的改动。版本号与 `pom.xml` 的 `<version>` 保持一致，发布时在 `main` 上打同名 tag。

## v2.0.0 —— 第二版（2026-10-16）

第二版的两条主线：**审查范围从 `.java` 扩展到配置文件、脚本与前端源码**（解析器插件层 +
15 条文本规则），以及**控制台重写为 Vue 3 + Element Plus 单页应用**。第一版停留在
`0.1.0-SNAPSHOT`、未打 tag，v2.0.0 是首次正式发布的版本。

### 一、多语言 / 多文件类型支持（本次核心改动）

**语言识别（`cq-common`）**

- 新增 `com.cq.common.lang.Language` 枚举：18 种语言 / 类别 + `UNKNOWN`，按扩展名判定，
  `Dockerfile`、`Makefile`、`id_rsa` 一类按文件名判定；识别不出的一律跳过，
  二进制、图片、jar 包因此天然不会被读入，无需内容嗅探。

**解析器插件层（`cq-parser`）**

- 新增 `SourceParser` 接口与 `SourceParserRegistry` 注册表，按文件类型派发解析器：
  - `JavaSourceParser`：Java 保留完整 AST 分析（类型 / 方法 / 调用图 / 圈复杂度）；
  - `StructuredSourceParser`：`.json` / `.yml` / `.yaml` / `.xml` 做良构校验
    （Jackson / SnakeYAML / JAXP），带 XXE 与 YAML 标签注入防护；
  - `PlainTextSourceParser`：其余文本文件按行透传，交给文本规则处理。
- `ParsedFile` 携带 `language` 与解析问题（新增 `ParseProblems`），扫描不再假设「文件都是 Java」。

**15 条文本规则（`cq-rule` 新增 `text` 子包，四维规则 42 → 57 条）**

| 维度 | 新增规则 |
| --- | --- |
| Bug（+4） | `BUG.CONFLICT_MARKER`、`BUG.DEBUG_STATEMENT`、`BUG.TODO_MARKER`、`BUG.INVALID_STRUCTURED_FILE` |
| 安全（+5） | `SEC.CONFIG_PLAINTEXT_SECRET`、`SEC.PRIVATE_KEY_MATERIAL`、`SEC.INSECURE_HTTP_ENDPOINT`、`SEC.SHELL_DANGEROUS_COMMAND`、`SEC.DOCKER_RUN_AS_ROOT` |
| 性能（+3） | `PERF.SQL_SELECT_STAR`、`PERF.SQL_WRITE_WITHOUT_WHERE`、`PERF.SQL_LEADING_WILDCARD` |
| 规范（+3） | `STYLE.LINE_TOO_LONG`、`STYLE.TRAILING_WHITESPACE`、`STYLE.MISSING_FINAL_NEWLINE` |

- **规则按语言过滤**：`Rule.languages()` 声明适用范围，`RuleEngine` 逐条过滤，
  Java 的 AST 规则不会在 YAML 上空跑，文本规则也不会越界到未声明的语言。
- `CredentialPatterns` 抽为公共类，供 AST 规则与文本规则共用同一份凭证特征。
- 文本规则的豁免比 AST 规则更多（缺少类型信息兜底）：XML 不参与「明文 http 地址」检查
  （命名空间不是接口地址）、Markdown 排除在行宽与行尾空白之外（表格与硬换行语法）、
  示例文件与 `${VAR}` 占位符一律不报 —— 每条例外都有对应的负例测试。

**扫描链路（`cq-scan` / `cq-repo` / `cq-web` / `cq-admin`）**

- `ScanService` 改用 `SourceParserRegistry`；目录、上传、粘贴、GitHub 四条来源统一按语言收集文件。
- 仓库路径过滤由 `filterJavaPaths` 改为 `filterSourcePaths`：**「什么值得扫」只有一处定义**，
  `GitHubRepoClient` 只如实返回仓库里的全部路径（`pathsAt` / `changedPaths`）。
- 修复单文件扫描的静默失效：`relativize` 对单个文件返回空路径，会让语言识别失败、
  问题列表的文件列变空白，现直接取文件名。
- 默认忽略名单扩充 `node_modules`、`dist`、`build`、`*.min.js`、各类 lock 文件与 `*.pb.java` ——
  放开文件类型后这份名单是控制仓库扫描文件数的关键；`RuleThresholds` 的默认值与
  `schema.sql` 种子值保持一致。
- 新增阈值「单行最大长度」（默认 120），可在「规则配置」页调整。

### 二、控制台

- 整体重写为 Vue 3 + Element Plus + Vite 单页应用：六个视图、亮/暗双主题、自绘趋势图、
  history 路由与筛选条件写入 URL（详见 README「快速开始」）。
- 「规则配置」页规则表新增**适用语言**列，阈值区新增「单行最大长度」。
- 概览页的上传与粘贴支持全部受支持类型：文件选择框 `accept` 列表与后端 `Language` 枚举一一对应，
  类型标签、提示文案同步更新。
- 「大模型配置」页可在控制台增删 / 启停 API Key，启用即时生效、无需重启，接口只返回掩码。

### 三、数据库

- `docs/sql/schema.sql`：种子数据补上「单行最大长度」阈值与扩充后的忽略路径。
- 新增 `docs/sql/upgrade-multilingual.sql`：已有库的幂等增量升级脚本（不执行也能运行，
  仅「单行最大长度」不会出现在阈值参数表中）。

### 四、工程与文档

- 版本号由 `0.1.0-SNAPSHOT` 提升为 **2.0.0**，11 个 Maven 模块统一；README 中的
  jar 名同步为 `cq-web-2.0.0.jar`。
- README 按第二版重写「支持的文件类型」「规则清单（57 条）」「测试基线」等章节，
  并补充数据库升级说明；本文件开始记录版本改动。
- 测试规模：后端 JUnit 5 用例 **181 → 252**（新增语言识别、解析器派发、
  多文档 YAML、带 DOCTYPE 的 MyBatis 映射文件、YAML 标签注入与 XXE 防护等）；
  前端 Playwright 用例 **15 → 17**（新增粘贴 YAML 可命中问题、不支持的扩展名被明确拒绝）。

## v1.0.0 —— 第一版（基线，版本号 `0.1.0-SNAPSHOT`，未打 tag）

- 10 个 Maven 模块的工程骨架、Spring Boot 启动模块与 `Issue` / `Result` 统一 Schema。
- JGit 仓库接入：克隆、两次提交间 Diff 与增删行统计。
- JavaParser AST 解析：类型 / 方法 / 参数 / 返回类型 / 调用图 / 圈复杂度。
- 42 条四维规则（Bug / 安全 / 性能 / 规范）+ 规则引擎、局部类型索引、结果去重排序。
- Code Agent：LLM 客户端抽象（OpenAI 兼容 / 离线兜底）、语义确认与成因分析。
- 修复建议生成（确定性模板 + Code Agent 两层）、采纳 / 误报反馈。
- 报告与趋势、规则与阈值管理、MySQL 持久化与 REST 接口。
- 四种代码来源：服务器目录、浏览器上传、粘贴代码、GitHub 仓库在线直取（全量 / 增量，不克隆不落盘）。
