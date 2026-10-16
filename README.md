# 基于大语言模型的智能代码质量分析与自动修复建议系统

结合 **AST 结构化解析**与 **LLM 语义理解**，对 Git 仓库代码进行 **Bug、安全漏洞、性能问题、编码规范** 四个维度的智能分析，形成「发现问题 — 解释成因 — 给出修复」的完整闭环，可作为日常开发流程中的智能审查工具。

> **当前版本 v2.0.0（第二版）**。相比第一版，第二版把审查范围从 `.java` 扩展到配置文件、脚本、前端源码等文本文件（解析器插件层 + 15 条文本规则），并沿用 Vue 3 控制台。逐项改动见 [`CHANGELOG.md`](CHANGELOG.md)。

## 一、项目背景与意义

传统静态检查工具（Checkstyle、SonarQube 规则集等）存在三个明显短板：

- **规则固定**：只能匹配预设模式，难以覆盖语义级缺陷；
- **误报较多**：只能指出「疑似问题」，不解释成因，开发者需要自行判断；
- **修复脱节**：给出告警但不提供贴合上下文的修复方案。

而人工代码评审虽然理解力强，却成本高、标准不一、难以规模化。

本系统将二者优势互补：**AST 负责精确定位，LLM 负责语义理解与修复建议生成**。结构化解析提供准确的文件、行号、方法切片与调用链上下文；大模型在此基础上做语义级判断并输出成因解释与修复 Diff。相比纯 LLM 分析，AST 切片既提升了定位精度，又显著压缩了上下文长度、降低了调用成本。

## 二、总体架构

处理链路如下：

```
Git 仓库接入 → 增量代码获取 → AST 解析 → 分析引擎 → 问题聚合与置信度过滤 → 修复建议生成 → 审查报告输出
                                        ├─ 规则引擎
                                        └─ Code Agent
```

各环节职责：

| 环节 | 职责 |
| --- | --- |
| 接入模块 | 经 JGit 对接 Git 仓库，支持按**提交（Commit）**与**分支差异（Diff）**两种扫描模式，默认只扫描增量变更以控制成本 |
| 解析模块 | 按文件类型派发解析器：Java 基于 JavaParser 构建 AST，提取方法、调用链、异常处理等语义单元；JSON / YAML / XML 做良构校验；其余文本文件按行扫描 |
| 分析模块 | 规则引擎执行确定性检查，并按语言过滤适用规则；Code Agent 对疑似问题做语义确认与成因解释 |
| 建议模块 | 按问题类型生成修复建议与示例代码（Diff 形式），标注置信度供开发者采纳 |

## 三、模块划分

后端共 8 个功能模块，划分为 10 个 Maven 子模块：

| Maven 模块 | 对应功能模块 | 职责 | 状态 |
| --- | --- | --- | --- |
| `cq-common` | 公共 Schema | `Issue` 问题对象、`Result` 统一返回、`ast` 包 AST 摘要模型、`model` 包各类配置与数据模型 | ✅ 已实现 |
| `cq-repo` | 仓库接入 | JGit 克隆仓库、获取两次提交间 Diff 并统计增删行；GitHub 在线直取（trees / compare / raw API，不克隆不落盘） | ✅ 已实现 |
| `cq-parser` | AST 解析 | 解析器插件层：Java 走 JavaParser 提取类型/方法/调用图/圈复杂度，JSON/YAML/XML 做良构校验，其余按纯文本透传 | ✅ 已实现 |
| `cq-rule` | 规则引擎 | 四维确定性规则检查，57 条规则（42 条 AST 规则 + 15 条文本规则）+ 局部类型索引 + 结果去重排序 | ✅ 已实现 |
| `cq-scan` | 扫描调度 | 扫描任务编排、状态机、问题落库、查询与状态更新 | ✅ 已实现 |
| `cq-agent` | Code Agent | LLM 客户端抽象（OpenAI 兼容 / 离线兜底）+ 运行时可切换的密钥配置 + 语义确认与成因分析 | ✅ 已实现 |
| `cq-suggestion` | 修复建议 | 确定性模板 + Code Agent 两层生成、Diff 构造、采纳/误报反馈 | ✅ 已实现 |
| `cq-report` | 报告与趋势 | 报告生成、问题密度/类别分布统计、跨任务质量趋势 | ✅ 已实现 |
| `cq-admin` | 规则与阈值管理 | 规则启停、阈值配置、忽略列表、反馈统计 | ✅ 已实现 |
| `cq-web` | 启动模块 | Spring Boot 启动入口、38 个 REST 接口、单页应用路由回退 | ✅ 已实现 |
| `cq-web-frontend` | 前端控制台 | 独立的 npm 工程（Vue 3 + Element Plus + Vite）：六个视图、双主题、自绘趋势图，由 Maven 触发构建 | ✅ 已实现 |

> 状态说明：✅ 已实现并验证 · 🟡 骨架可运行 · ⬜ 待开发

## 四、基本功能设计

1. **仓库接入与扫描管理**：配置仓库地址与凭证自动拉取；支持 Webhook 触发提交级扫描与定时全量扫描；展示扫描任务状态与耗时。
2. **四维智能分析**：Bug 检测（空指针、并发问题、资源泄漏）、安全漏洞检测（SQL 注入模式、硬编码密钥）、性能问题（循环内查库、大对象冗余拷贝）、编码规范检查；每条问题输出文件、行号、类别、严重等级与成因说明。
3. **修复建议生成**：Code Agent 针对问题生成修改建议与示例代码，以 Diff 展示修改前后对比，支持一键复制采纳；低置信度建议自动标注「供参考」。
4. **审查报告与趋势**：按仓库、提交生成报告，统计问题密度、类别分布与修复率；跨版本对比形成质量趋势。
5. **规则与阈值管理**：管理员配置启用规则、严重等级阈值与扫描策略；支持忽略列表与误报标记反馈。

## 五、创新拓展功能设计

1. **AST + LLM 双引擎融合**：规则引擎保证确定性问题的零漏报，LLM 处理语义级问题并输出成因，结果按置信度融合抑制误报。
2. **修复验证闭环（进阶）**：对可自动生成的修复 Patch 尝试编译/测试验证，只有通过验证才标记为「已验证修复」。
3. **增量上下文分析**：基于 Git Diff 提取变更文件及其调用方，仅分析受影响范围，显著降低大模型调用成本。
4. **误报反馈自学习**：开发者「标为误报 / 采纳建议」的行为回流，用于阈值与规则配置调优。
5. **多语言扩展**：以**解析器插件**方式扩展语言支持 —— `SourceParser` 接口 + 注册表按文件类型派发解析器，规则通过 `Rule.languages()` 声明适用范围，引擎逐条过滤。Java 保留完整 AST 分析，`.yml` / `.json` / `.xml` / `.sql` / `.sh` / `.vue` / `.py` / `Dockerfile` 等则走文本与结构化校验规则（见「支持的文件类型」）。

## 六、关键技术方案

- **AST 辅助定位**：以方法/类等 AST 语义单元为切片送入 LLM，控制上下文长度并提升定位精度。
- **双引擎结果融合**：规则命中与 LLM 判定按问题类型加权融合，输出统一置信度并设置采纳门槛。
- **增量扫描**：通过 Git Diff 得到变更文件集与关联调用链，仅分析受影响范围，量化成本节省。
- **问题分类 Schema**：定义统一的问题对象模型（类别/严重级/位置/成因/建议），支撑报告与趋势统计。

## 七、技术栈

| 层次 | 技术选型 | 当前状态 |
| --- | --- | --- |
| 后端 | Spring Boot 3 、MyBatis-Plus 、MySQL | ✅ 已落地 |
| 后端 | Redis（扫描任务缓存） | ⬜ 规划中 |
| 代码分析 | JavaParser（AST）、JGit（Git API）、规则引擎 | ✅ 已落地 |
| AI 能力 | LLM（代码理解与修复建议）、Prompt 工程、置信度融合 | ✅ 已落地 |
| 前端 | Vue 3 + Element Plus + Vite，灰阶为底、彩色克制的双主题控制台 | ✅ 已落地 |
| 前端 | 亮/暗两套主题，配色对比度与分类色均经脚本校验 | ✅ 已落地 |
| 集成 | GitLab / Gitea Webhook | ⬜ 规划中 |

**当前工程基线**：项目版本 2.0.0 · Java 21 · Spring Boot 3.4.4 · Maven 多模块 · JavaParser 3.26.4 · JGit 6.10.0 · MyBatis-Plus 3.5.9 · MySQL 8.0

**测试基线**：

- 后端 JUnit 5 共 252 个用例 —— AST 解析（类型/方法/调用图/圈复杂度/容错）、
  57 条规则每条配「正例命中 + 负例不误报」（负例是重点）、语言识别与解析器派发
  （含多文档 YAML、带 DOCTYPE 的 MyBatis 映射文件、YAML 标签注入与 XXE 防护）、
  修复模板匹配、上传内容校验、仓库地址解析与在线文件筛选、
  大模型配置的优先级与回退（含密钥掩码边界）
- 前端 `frontend-test/` 用 Playwright 驱动**真实浏览器**跑控制台，17 条用例覆盖扫描全流程
  （含粘贴 YAML 也能命中问题、不支持的扩展名被明确拒绝）、分页过滤、详情、规则与大模型配置、
  主题切换与深链直达（不含在 `mvn test` 中，需先启动服务再 `npm test`）；
  另有 `npm run shots` 出亮/暗两套截图供人眼核对视觉

## 八、快速开始

环境要求：JDK 21、Maven 3.6+、MySQL 8.0。
前端由 Maven 自动构建（`frontend-maven-plugin` 会自带一份 Node），**构建机无需预装 Node**，
代价是首次构建需要联网下载一次 Node 与 npm 依赖。

### 1. 初始化数据库

```bash
mysql -uroot -p < docs/sql/schema.sql
# 或使用 Docker 中的 MySQL：
docker exec -i mysql mysql -uroot -p123456 --default-character-set=utf8mb4 < docs/sql/schema.sql
```

脚本会创建 `code_quality` 库、15 张表，并写入默认阈值配置与管理员账号。
数据库连接参数在 `cq-web/src/main/resources/application.yml` 中配置。

### 2. 构建与启动

```bash
# 全量构建（首次会下载依赖 + 自带 Node + npm 依赖）
mvn clean install

# 只跑后端（252 个用例，跳过前端构建）
mvn test -Dskip.frontend

# 启动 Web 服务，默认端口 8080（会先构建前端，产物直接进 target/classes/static）
mvn -pl cq-web spring-boot:run
# 或使用打包后的 fat jar
java -jar cq-web/target/cq-web-2.0.0.jar

# 前端端到端测试（需先启动服务，见 frontend-test/README.md）
# cd frontend-test && npm install && npm run install-browser && npm test
```

改前端时的迭代方式（带热更新，不用等 Maven）：

```bash
mvn -pl cq-web spring-boot:run -Dskip.frontend     # 后端常驻 8080
cd cq-web-frontend && npm install && npm run dev   # Vite 开发服务器 5173，/api 已代理到 8080
```

只改样式/组件后用 `npm run build` 重新构建即可刷新正在运行的服务
（vite 直接写 `target/classes/static`，无需重启）。

### 3. 打开控制台

浏览器访问 **http://localhost:8080/** ，在「概览」页选择一种代码来源发起审查 —— 输入服务器目录路径、
上传本地文件、直接粘贴一段代码，或输入一个 GitHub 仓库地址；随后在「问题列表」进入
「问题详情」查看成因与修复建议，在「报告与趋势」看质量走势，在「规则配置」调规则、阈值与忽略列表，
在「大模型配置」接入或切换大模型。

「规则配置」页的规则表直接标出每条规则的**适用语言**（覆盖全部文本文件的规则合并显示为
「全部文本文件（N 种）」），阈值区可调「单行最大长度」，忽略列表即控制仓库扫描文件数的名单。

右上角可切换**亮色 / 暗色**主题（首次访问跟随系统，选择记在本地）。路由是 history 模式，
`/issues/123` 这类地址可以直接分享或刷新；问题列表的筛选条件也写在 URL 里，后退键可用。

### 4. 配置大模型（可选）

默认未配置 API Key 时，系统使用**离线启发式**实现，扫描、建议生成、报告链路均可完整运行，
但成因分析为基于规则元数据的确定性模板。

接入真实模型推荐直接在控制台操作：打开 **「大模型配置」页**，填写名称、接口地址、模型与密钥并保存，
启用中的配置**立即生效、无需重启**；可保存多条（如 DeepSeek / 通义 / Kimi / 智谱）并在页面上随时
切换或删除。密钥明文存于本地数据库，接口只返回掩码（如 `sk-…cdef`）。

也可以在 `application.yml` / 环境变量中兜底配置，其优先级低于控制台配置：

```yaml
cq:
  llm:
    base-url: https://api.deepseek.com/v1   # 通义/Kimi/智谱等兼容服务同理
    api-key: sk-xxxx
    model: deepseek-chat
```

运行时优先级为 **启用中的数据库配置 > application.yml > 离线启发式**：数据库配置被删除或字段不完整、
乃至数据库不可用时，都会逐级回退，不会让建议生成整体失败；调用失败也不静默降级，错误原因写入
`llm_call_log` 供排查。

### 四种代码来源

控制台首页支持四种待审查代码的输入方式，四者走**完全相同**的解析、规则检查与落库链路：

| 方式 | 接口 | 说明 |
| --- | --- | --- |
| 服务器目录 | `POST /api/scan/start` | 扫描服务器上可访问的目录或单个文件，按扩展名递归收集受支持的文件 |
| 上传本地文件 | `POST /api/scan/upload` | 浏览器多选文件（multipart），一次最多 50 个、单文件不超过 2 MB |
| 粘贴代码 | `POST /api/scan/snippet` | 直接提交一段源码或配置，语言按文件名的扩展名识别，不填文件名则按 Java 处理 |
| GitHub 仓库 | `POST /api/scan/repo` | 输入仓库地址**在线直取**（不克隆、不落盘）：全量扫描 head 提交下的受支持文件，增量扫描仅分析两次提交之间变更的文件 |

上传与粘贴的内容**只在内存中解析，不落盘**：

- 文件名只取最后一段（`../../etc/passwd.java` → `passwd.java`），且不参与任何文件系统操作，从根上排除路径穿越
- 不支持的扩展名会被拒绝并明确列出，而不是放行后产生一堆解析错误让人误以为分析失败
- 文件数、单文件体积都有上限，避免一次大上传拖垮服务

### 支持的文件类型

文件类型由扩展名（`Dockerfile`、`Makefile`、`id_rsa` 这类按文件名）识别，识别不出的直接跳过 ——
二进制、图片、jar 包天然不会被读进来，无需再做内容嗅探。

| 分析方式 | 文件类型 |
| --- | --- |
| **完整 AST**（JavaParser） | `.java` |
| **良构校验**（Jackson / SnakeYAML / JAXP） | `.json`、`.yml`、`.yaml`、`.xml` |
| **文本规则**（逐行扫描） | `.properties`、`.ini`、`.env`、`.cfg`、`.conf`、`.toml`、`.sql`、`.sh`、`.bash`、`.zsh`、`.js`、`.jsx`、`.mjs`、`.cjs`、`.ts`、`.tsx`、`.vue`、`.py`、`.go`、`.css`、`.scss`、`.less`、`.md`、`.pem`、`.key`、`Dockerfile`、`Makefile`、`id_rsa` 等 |

语言识别与「文件 → 解析器 → 规则」的派发由 `com.cq.common.lang.Language` 与
`com.cq.parser.SourceParserRegistry` 统一负责；规则通过 `Rule.languages()` 声明适用范围，
引擎逐条过滤，因此 Java 的 AST 规则不会在 YAML 上空跑，文本规则也不会越界到未声明的语言。

> **升级已有数据库**：本功能新增了一个阈值配置项并扩充了默认忽略路径。新装环境直接跑
> `schema.sql` 即可；已经在跑的库请额外执行一次
> `docs/sql/upgrade-multilingual.sql`（幂等，不执行也能运行，只是「单行最大长度」不会
> 出现在阈值参数表里、无法在页面上调整）。

GitHub 仓库扫描同样**不落盘**：源码经 GitHub 接口按需拉取到内存，磁盘上不留克隆目录。几条边界：

- 仅支持公开的 GitHub 仓库（`https://github.com/owner/repo`、`git@github.com:owner/repo` 或
  `owner/repo` 简写均可，后两者解析为 owner/repo 后走 https）；私有仓库需要凭证，暂未支持
- 未认证调用 api.github.com 限流 60 次/小时（文件正文走 raw 域不计入该额度），配置
  `cq.repo.github-token` 后提升为 5000 次/小时；单次扫描的受支持文件数上限为
  `cq.repo.max-files`（默认 1000），超出直接失败而不是静默截断。放开文件类型后纳入统计的
  文件变多，因此默认忽略路径也补齐了 `node_modules`、`dist`、`build`、`*.min.js` 与各类
  lock 文件 —— 这份名单是控制文件数的关键，可在「规则配置」页调整
- 增量扫描默认基线 = 该仓库**上次扫描记录的提交**（`repo.last_commit_id`），首次扫描取 head 的父提交；
  仓库只有一次提交时会明确提示改用全量

### 主要接口

| 接口 | 说明 |
| --- | --- |
| `POST /api/scan/start` | 提交目录扫描任务，异步执行并返回任务 ID |
| `POST /api/scan/upload` | 上传本地文件审查（按扩展名判定类型） |
| `POST /api/scan/snippet` | 粘贴代码审查 |
| `POST /api/scan/repo` | 在线扫描 GitHub 仓库（全量 / 增量，不克隆不落盘） |
| `GET /api/scan/tasks/{id}` | 查询任务进度与结果统计 |
| `GET /api/issues?taskId=&type=&severity=` | 按条件查询问题 |
| `POST /api/suggestions/{issueId}` | 为问题生成修复建议 |
| `POST /api/suggestions/feedback` | 提交采纳 / 误报反馈 |
| `GET /api/reports/{taskId}` | 获取任务报告 |
| `GET /api/reports/trend` | 质量趋势 |
| `GET /api/rules` | 规则列表与启停状态 |
| `GET /api/llm/keys` | 大模型 API Key 配置列表（密钥为掩码） |
| `POST /api/llm/keys` | 新增配置并立即生效（表中首条自动启用） |
| `PATCH /api/llm/keys/{id}/activate` | 启用某条配置（立即生效，无需重启） |
| `DELETE /api/llm/keys/{id}` | 删除配置 |
| `POST /api/ast/parse` | 直接解析 Java 源码返回 AST 摘要（JavaParser 专用，不经过解析器注册表） |

### 直接使用 AST 解析能力（`cq-parser`）

```java
AstParserService parser = new AstParserService();

AstSummary summary = parser.parseFile("/path/to/Any.java");
// 需要细粒度事实（字符串字面量、注解、修饰符）时用 parseDetailed，可拿到原始 AST 与源码
ParsedFile detailed = parser.parseDetailed(new File("/path/to/Any.java"));

summary.getClasses();                       // 类型结构：类/接口/枚举/记录/匿名类
for (MethodInfo m : summary.getMethods()) {
    m.getName();                            // 方法名
    m.getParameters();                      // 参数列表（类型/名称/可变参数）
    m.getReturnType();                      // 返回类型
    m.getCyclomaticComplexity();            // 圈复杂度
    m.getCalls();                           // 该方法内的调用点
}
summary.getCallGraph().getEdges();          // 方法间调用关系（调用方 → 被调方 + 行号）
summary.getMaxComplexity();                 // 派生统计：最大/平均圈复杂度
summary.getParseErrors();                   // 语法错误（不抛异常，返回已解析的部分结果）
```

> 调用图精度说明：为避免引入完整类路径依赖，`cq-parser` 未启用 JavaParser 的 SymbolSolver，只做**文件内**的启发式解析。无 scope 或 `this.` 的调用若能唯一匹配到同类中的同名方法（必要时按实参个数区分重载），边的 `resolved=true` 且目标为方法签名；跨文件调用无法定位到具体重载，此时 `resolved=false`，目标退化为 `scope.methodName` 文本标识。
>
> 这一限制同样影响规则引擎，因此 `cq-rule` 内置了 `LocalTypeIndex`（按方法作用域索引「变量名 → 声明类型」）来补足类型信息。它是降噪的关键：例如 `"SELECT ... WHERE id=" + id` 在 `id` 为 `int` 时不算注入，`List.contains` 与 `String.contains` 也能借此区分。

## 九、任务量与工作量规划

| 量化维度 | 规划规模 |
| --- | --- |
| 后端功能模块 | 8 个：仓库接入、扫描调度、AST 解析、规则引擎、Code Agent 分析、修复建议、报告趋势、规则与阈值管理 |
| 代码来源 | 4 种：服务器目录、浏览器上传文件、直接粘贴代码、GitHub 仓库地址（在线直取） |
| 规则模板库 | 4 类维度合计不少于 40 条（Bug / 安全 / 性能 / 规范各 10 条以上） |
| 数据库表规模 | 约 15 张（仓库、扫描任务、文件快照、问题记录、修复建议、采纳反馈、报告、规则配置、大模型密钥配置等） |
| REST 接口规模 | 约 28 个 |
| 前端页面 | 6 个视图（概览、问题列表、问题详情、报告与趋势、规则配置、大模型配置）+ 亮/暗两套主题 |
| 评测集建设 | 含缺陷样例不少于 200 个（覆盖四类问题的正例与负例），用于精确率 / 召回率评估 |
| 中间件与集成点 | 4 项：Redis 、JGit 、Webhook 接收、LLM 调用 |
| 评测与测试 | 双引擎相对纯规则引擎的误报率对比、修复建议采纳率抽样、增量扫描成本对比 |

阶段任务划分：

| 阶段 | 周期 |
| --- | --- |
| 需求与问题 Schema | 2 周 |
| AST 解析与规则引擎 | 3 周 |
| Code Agent 与建议生成 | 3 周 |
| 报告与趋势 | 2 周 |
| 评测集与联调优化 | 3 周 |

## 十、预期成果与考核指标

完成仓库接入、四维分析、修复建议、质量报告核心闭环，形成可对接真实 Git 仓库的智能审查系统。

规划考核指标：

- 四类问题（Bug / 安全 / 性能 / 规范）的**精确率与召回率**（在自建评测集上）
- **误报率**相对纯规则引擎的下降幅度
- 修复建议**抽样采纳率**
- **端到端扫描耗时**

## 十一、开发进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 工程骨架、Spring Boot 启动模块 | ✅ |
| 2 | 问题对象 Schema（`Issue` / `Result`） | ✅ |
| 3 | JGit 仓库接入：克隆与 Diff 增量获取 | ✅ |
| 4 | JavaParser AST 解析：方法与调用图、圈复杂度 | ✅ |
| 5 | 规则引擎（42 条四维规则 + 单元测试） | ✅ |
| 5.1 | 多语言扩展：解析器插件层 + 15 条文本规则（四维规则增至 57 条） | ✅ |
| 6 | Code Agent 与修复建议生成 | ✅ |
| 7 | 报告与趋势、规则与阈值管理 | ✅ |
| 8 | MySQL 持久化、REST 接口、前端控制台 | ✅ |
| 9 | 控制台重写为 Vue 3 + Element Plus：双主题配色、URL 路由、Playwright 端到端测试 | ✅ |
| 10 | 多语言扩展：解析器插件层 + 15 条文本规则，审查范围扩展到配置文件与脚本 | ✅ |
| 11 | 评测集建设与联调优化 | ⬜ |

### 规则清单（57 条）

| 维度 | 条数 | 规则 |
| --- | --- | --- |
| Bug | 14 | 空 catch、仅 printStackTrace、finally 中 return、资源未关闭、`==` 比较字符串、BigDecimal.equals、Optional.get 未检查、静态日期格式化器、自比较、整数除零；**遗留冲突标记、遗留调试输出、未完成标记、配置文件语法错误** |
| 安全 | 15 | SQL 拼接注入、硬编码凭证、命令注入、弱哈希、不安全随机数、XXE、不安全反序列化、信任所有证书、敏感信息进日志、ECB 分组模式；**配置明文凭证、提交了私钥、明文 http 地址、危险 shell 命令、容器以 root 运行** |
| 性能 | 13 | 循环内查库、循环内字符串拼接、日志未用占位符、循环内编译正则、循环内 `List.contains`、`new String(...)`、循环内 sleep、循环内创建日期格式化器、循环内集合拷贝、循环内 `String.format`；**`SELECT *`、写操作缺少 WHERE、LIKE 前导通配符** |
| 规范 | 15 | 方法过长、圈复杂度过高、类过长、参数过多、类/方法/常量命名、魔法数字、缺 Javadoc、嵌套过深、未使用 import、字段名过短；**单行过长、行尾空白、文件末尾缺换行** |

加粗的 15 条是文本规则，其余 42 条建立在 JavaParser 的 AST 之上。两者通过
`Rule.languages()` 划分范围：AST 规则默认只作用于 `.java`，文本规则按需声明 ——
「单行过长」「行尾空白」「遗留冲突标记」「提交了私钥」这类与语言无关的判据作用于全部语言，
配置凭证、SQL、Dockerfile 这几条则收窄到对应类型。

> 规则设计原则：**宁可少报也不要刷屏**。因此多条规则内建了必要的白名单与例外 ——
> 常量命名豁免 `log`/`logger`/`serialVersionUID`；嵌套深度不把 `else if` 计入层级
> （它在 AST 中是嵌套 `IfStmt`）；浮点自比较豁免（`x == x` 是标准 NaN 判断）；
> 循环类规则跳过 lambda 与匿名类内的调用（延迟执行，非每轮迭代）。
>
> 文本规则同样如此，且因为缺少类型信息兜底，豁免写得更多：XML 直接不参与「明文 http 地址」
> 检查（`xmlns="http://maven.apache.org/..."` 是命名空间而非接口地址）；Markdown 排除在
> 行宽与行尾空白之外（表格无法折行、行尾两个空格是硬换行语法）；只有 `<<<<<<<` 一类的方向
> 标记才算冲突（单独一行等号在 Markdown 里是标题下划线）；`.env.example` 一类的示例文件与
> `${DB_PASSWORD}` 一类的占位符一律不报。
> 这些例外均有对应的负例测试保护。

## 十二、仓库结构

```
code-quality-analyzer/
├── pom.xml                   # 父 POM，统一管理版本（2.0.0 / Java 21 / Spring Boot 3.4.4）
├── CHANGELOG.md              # 版本改动记录（v2.0.0 第二版：多文件类型支持）
├── docs/
│   └── sql/
│       ├── schema.sql        # 数据库 Schema（15 张表 + 初始阈值与账号）
│       └── upgrade-multilingual.sql  # 增量升级：已有库补上多文件类型新增的阈值行与忽略路径
├── cq-common/                # 公共模块：Issue 问题对象、Result 统一返回、ast 摘要模型、model 数据模型、lang 语言枚举
├── cq-repo/                  # Git 仓库接入（JGit）
├── cq-scan/                  # 扫描调度与问题落库（按语言收集文件）
├── cq-parser/                # 解析：JavaParser AST + 解析器插件层（SourceParser / 注册表 / 结构化校验）
├── cq-rule/                  # 规则引擎（57 条规则 + 局部类型索引；text 子包为文本规则）
├── cq-agent/                 # Code Agent（LLM 客户端抽象与语义分析）
├── cq-suggestion/            # 修复建议（模板 + Code Agent 两层）
├── cq-report/                # 报告与趋势
├── cq-admin/                 # 规则与阈值管理
├── cq-web/                   # Spring Boot 启动模块（含唯一的前端相关后端改动：SpaWebConfig 路由回退）
├── cq-web-frontend/          # 前端源码：Vue 3 + Element Plus + Vite（构建产物不进仓库，直接写 target/classes/static）
│   └── src/
│       ├── styles/           # 设计令牌（亮/暗两套）与 Element Plus 主题映射
│       ├── api/              # 统一的请求封装与各模块接口
│       ├── components/       # 徽标、维度色点、指标条、轻提示
│       └── views/            # 概览 / 问题列表 / 问题详情 / 报告与趋势 / 规则配置 / 大模型配置
└── frontend-test/            # 前端端到端测试（Playwright 驱动真实浏览器）
```

