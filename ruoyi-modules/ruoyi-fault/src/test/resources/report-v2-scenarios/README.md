# Report V2 场景样例

所有样例使用同一窗口 `2026-08-10 09:00:00` 至 `09:04:00`、60 秒标称采样周期和 80% 完整度阈值。JSON 是完整结构化 `OperationReportResult`，Markdown 是同一对象经 `MarkdownOperationReportRenderer.renderFull` 的输出。

| 场景 | 固定遥测设计 | 预期状态 | 样例 |
| --- | --- | --- | --- |
| 正常运行 | 4 条稳定样本，无代码 | `NORMAL` | `normal-running.json` / `normal-running.md` |
| 已恢复报警 | `A07089` 出现在 09:01、09:02，09:03 清除 | `ATTENTION`，`active=false` | `recovered-alarm.json` / `recovered-alarm.md` |
| 当前活动报警 | `A07089` 持续至 09:03 | `ATTENTION`，`active=true` | `active-alarm.json` / `active-alarm.md` |
| F 类故障 | 项目既有测试代码 `F30005` 持续至窗口末尾 | `FAULT` | `fault-f30005.json` / `fault-f30005.md` |
| 数据不足 | 4 分钟窗口仅 1 条样本，完整度 25% | `UNKNOWN`，无 metrics/trends | `insufficient-data.json` / `insufficient-data.md` |
| 多事件/明显变化 | `A07089` 恢复后 `F30005` 活动；实际功率 10→20→40→80 | `FAULT` | `multiple-events-change.json` / `multiple-events-change.md` |

`ReportV2ScenarioTest` 默认逐字校验这些样例。需要人工更新时，可显式传入 `-DreportV2.sampleOutput=<安全输出目录>` 导出候选文件，再审查后提交。
