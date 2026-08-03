# 故障诊断遥测数据接入说明

> 状态：模拟数据阶段
> 更新日期：2026-08-03
> 适用范围：故障诊断确定性链路中的运行数据查询（`TelemetryQueryService`）

## 1. 当前架构

故障诊断读取遥测数据的链路是固定的、受控的：

```
FaultDiagnosisChatService（聊天入口，LLM 规划时间窗/未指定时用 now() 推算）
  → FaultDiagnosisOrchestrator（确定性编排）
    → TelemetryQueryService（权限/窗口校验、表路由、最新数据回退）
      → RealDataMapper（固定 SQL，@DS("dcma")，表名服务端受控）
        → dcma 数据源（10.108.12.164:3306/dcma）
```

- 遥测库与应用主库分离：主库存业务与证据表（`ruoyi-ai-agent`），遥测数据在 `dcma` 库。
- 遥测表没有租户字段，Mapper 上已用 `@InterceptorIgnore` 显式忽略租户与数据权限拦截。
- 查询只接受 设备 + 逆变器 + 时间窗，不接受 SQL；表名只来自配置且强校验（`^[A-Za-z0-9_]+$`）。

### 逆变器名称的确定性补全

底层遥测查询始终要求 设备 + 逆变器 两个维度，但聊天用户通常只知道设备名（例如
"G120电机1"），不知道也不应被要求提供逆变器名。因此：

- 用户**没有**在问题里指明逆变器时，`FaultDiagnosisChatService` 会调用
  `TelemetryQueryService.resolveInverterName(deviceName)`，按受控表查询该设备出现过的
  全部逆变器名（`SELECT DISTINCT inverter_name`）做确定性补全：
  - 唯一 → 直接用于诊断；
  - 多个 → 向用户返回澄清，列出候选逆变器名让其选择；
  - 无数据 → 返回"未找到设备遥测数据"的澄清。
- 用户**明确**指明逆变器时，直接使用其提供的值，不做补全。
- 补全只发生在通过设备白名单（`allowed-assets`）校验之后，未授权设备不会触达遥测表。

## 2. 模拟数据阶段（当前）

由于真实数据尚未到位，使用 `dcma` 库中三张模拟表，一台设备一张表：

| 设备 | 表 | 数据范围 | 备注 |
| --- | --- | --- | --- |
| G120电机1 | real_data_01 | 2026-07-19 14:50:01 ~ 15:04:11（245 行） | 含 A07089 报警段（写入 `alarm_code`） |
| G120电机2 | real_data_02 | 2026-07-19 10:21:36 ~ 10:41:46（345 行） | 含 F30899 故障段（写入 `fault_code`） |
| G120电机3 | real_data_03 | 2026-07-19 12:22:56 ~ 12:44:28（368 行） | 含 F07016 故障段（写入 `fault_code`） |

### 代码字段契约与 G120 归一化

数据契约：`F` 类故障码写入 `fault_code`，`A` 类报警码写入 `alarm_code`，
裸 `0` 或空值表示没有对应代码。（历史上 real_data_01 曾把 A07089 误写入
`fault_code`，已于 2026-08-03 修正为写入 `alarm_code`。）

后端在遥测分析边界（`TelemetryDataAnalyzer`）还有确定性的防御归一化：

- `F` + 数字归为故障码，`A` + 数字归为报警码，与字段名无关；
- 字段与代码类型不一致时（如 `fault_code=A07089`）按代码前缀归类，并记录
  数据质量问题到限制说明；
- 未知格式代码进入 `unknownCodes`，不升级为故障；
- 结果同时携带 `latestObservedAt`（最后一条有效遥测的业务时间）供回答层做时间锚点。

对应配置（`application.yml` 的 `fault.diagnosis`）：

```yaml
fault:
  diagnosis:
    allowed-assets: [G120电机1, G120电机2, G120电机3]
    telemetry-table: real_data          # 未命中专属表时的默认表
    device-telemetry-tables:            # 设备 -> 专属表路由（键含中文必须加方括号）
      "[G120电机1]": real_data_01
      "[G120电机2]": real_data_02
      "[G120电机3]": real_data_03
    latest-data-fallback-enabled: true  # 请求窗口无数据时回退到最近可用数据
    nominal-sampling-seconds: 4         # 模拟数据约 3~5 秒一条
```

### 最新数据回退

模拟数据的时间是固定的（2026-07-19），而聊天提问默认按 `now()` 推算时间窗，必然查空。
因此 `TelemetryQueryService` 在请求窗口查不到任何数据时，会自动改用该设备
**最近可用**的数据窗口（以表内最新 `create_time` 为终点、向前推 `default-window-minutes`，
并不早于表内最早记录）重新查询，保证始终有数据可查。

回退发生时：

- 结果标记 `fallbackToLatestData=true`；
- 回答的"实际分析时间"显示真实分析的窗口；
- "限制说明"中会注明：请求时间范围内没有遥测数据，已回退至最近可用数据；
- 证据链（fd_evidence 的遥测证据摘要）也会记录回退标记与实际窗口。

## 3. 真实数据到位后的切换步骤

真实数据的表结构只要与现有 `real_data` 一致（列名相同，允许多列），按以下步骤切换：

1. **确认数据落库**：确认真实数据写入 `dcma` 库的目标表（例如统一的 `real_data`），
   且 `device_name`、`inverter_name` 的取值与 `allowed-assets` 一致。
2. **清空按设备表路由**：删除或清空 `fault.diagnosis.device-telemetry-tables`，
   所有设备自动回退到 `telemetry-table`（默认 `real_data`）。
   如果真实数据仍然按设备分表，保留该映射并更新表名即可，代码无需改动。
3. **校准采样周期**：按真实采样间隔调整 `nominal-sampling-seconds`
   （影响完整度与数据缺口判断，进而影响"数据是否充足"标记）。
4. **决定是否保留回退**：真实数据是持续入库的实时数据时，
   可以保留 `latest-data-fallback-enabled: true`（提问不带时间时仍能给出最近数据），
   也可以改为 `false`，让无数据的窗口如实返回空结果。
5. **时区核对**：确认真实数据的 `timestamp`/`date`+`time` 字段含义与
   `fault.diagnosis.timezone`（当前 Asia/Shanghai）一致。

以上全部是配置修改，不需要改代码和表结构。

### 数据源位置

- 本地开发：`application-dev.yml` 的 `spring.datasource.dynamic.datasource.dcma`
- 生产/容器：`application-prod.yml` 同名配置，或 docker-compose 中 backend 的
  `SPRING_DATASOURCE_DYNAMIC_DATASOURCE_DCMA_URL / _USERNAME / _PASSWORD` 环境变量
- 遥测库地址、账号变更时只改这些地方，代码不动。

## 4. 验证方法

构建并重启后端后，向故障诊断 Agent（FAULT_DIAGNOSIS + DETERMINISTIC）提问：

- `诊断一下G120电机2最近的情况` —— 不指定时间，验证回退到 2026-07-19 10:21~10:41 的数据并观测到 F30899；
- `G120电机3在2026-07-19 12:30到12:40之间有什么故障？` —— 指定窗口命中 F07016；
- `G120电机1最近30分钟有没有故障？` —— 命中 A07089 报警段。

预期回答使用统一骨架（结论 → 最近一次观测 → 代码说明 → 建议 → 判断依据 → 结论边界），
包含：数据范围与时间锚点、分别展示的故障/报警码、每个 EV 编号的可读证据摘要。
发生回退时，结论第一句话为“当前状态无法确认”，并注明设备最新数据时间；
A 类代码只能被称为报警，不得描述为故障。
