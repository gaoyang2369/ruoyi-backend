package org.ruoyi.service.fault;

import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.model.DataQualitySummary;
import org.ruoyi.service.fault.model.FaultExecutionResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 设备诊断的统一回答骨架。
 * <p>
 * 骨架固定为：结论 → 最近一次观测 → 代码说明 → 建议 → 判断依据 → 结论边界。
 * 结论、时间边界、故障/报警列表和证据摘要由服务端确定性渲染，大模型草稿只插入
 * “代码说明与建议”部分；空内容按需隐藏。内部字段（partial、requestId、英文状态）
 * 不出现在回答主体中。
 */
final class FaultDiagnosisAnswerRenderer {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private FaultDiagnosisAnswerRenderer() {
    }

    /**
     * 渲染完整回答。
     *
     * @param middleSection 已通过安全校验的模型草稿；为 null 时使用确定性降级内容
     */
    static String render(FaultExecutionResult execution, String middleSection) {
        DiagnosisResult result = execution.diagnosisResult();
        StringBuilder out = new StringBuilder();
        appendConclusion(out, result);
        appendObservation(out, result);
        String middle = StringUtils.isBlank(middleSection) ? deterministicMiddle(execution) : middleSection.trim();
        if (!middle.contains("##")) {
            middle = "## 代码说明与建议\n\n" + middle;
        }
        out.append("\n\n").append(middle);
        appendEvidence(out, execution);
        appendBounds(out, execution);
        return out.toString().stripTrailing();
    }

    /**
     * 结论段：第一句话直接给出有故障、存在报警、未发现显式故障或当前无法确认，
     * 并附带时间锚点。时态完全由结构化字段决定。
     */
    private static void appendConclusion(StringBuilder out, DiagnosisResult result) {
        out.append("## 结论\n\n");
        List<String> faults = result.faultCodes();
        List<String> alarms = result.alarmCodes();
        if (result.status() == DiagnosisStatus.DATA_INSUFFICIENT) {
            out.append("数据不足，无法确认设备状态。");
        } else if (result.fallbackToLatestData()) {
            out.append("当前状态无法确认。");
        } else if (!faults.isEmpty() && !alarms.isEmpty()) {
            out.append("检测到 ").append(String.join("、", faults)).append(" 故障，同时存在 ")
                .append(String.join("、", alarms)).append(" 报警。");
        } else if (!faults.isEmpty()) {
            out.append("检测到故障：").append(String.join("、", faults)).append("。");
        } else if (!alarms.isEmpty()) {
            out.append("存在报警：").append(String.join("、", alarms)).append("，未发现 F 类故障码。");
        } else {
            out.append("未发现显式故障码或报警码。");
        }
        if (result.fallbackToLatestData()) {
            if (result.latestObservedAt() != null) {
                out.append("\n设备最新数据时间为 ").append(formatTime(result.latestObservedAt()))
                    .append("，属于历史数据。");
            }
        } else if (result.latestObservedAt() != null && result.status() != DiagnosisStatus.DATA_INSUFFICIENT) {
            out.append("\n截至 ").append(formatTime(result.latestObservedAt())).append("。");
        }
    }

    /**
     * 最近一次观测：分别展示故障与报警，不因故障优先级更高而隐藏报警。
     */
    private static void appendObservation(StringBuilder out, DiagnosisResult result) {
        out.append("\n\n## 最近一次观测\n\n");
        out.append("- 故障：")
            .append(result.faultCodes().isEmpty() ? "未发现 F 类故障码" : String.join("、", result.faultCodes()))
            .append('\n');
        out.append("- 报警：")
            .append(result.alarmCodes().isEmpty() ? "未发现报警码" : String.join("、", result.alarmCodes()))
            .append('\n');
        if (!result.unknownCodes().isEmpty()) {
            out.append("- 未识别代码：").append(String.join("、", result.unknownCodes()))
                .append("（未升级为故障）").append('\n');
        }
        out.append("- 数据范围：").append(formatTime(result.startTime())).append("—")
            .append(formatTime(result.endTime()));
    }

    /**
     * 判断依据：按诊断执行顺序输出用户可见证据，每个编号都带服务端生成的一句话摘要。
     */
    private static void appendEvidence(StringBuilder out, FaultExecutionResult execution) {
        out.append("\n\n## 判断依据\n\n");
        List<EvidenceReference> evidence = execution.userVisibleEvidence();
        if (evidence.isEmpty()) {
            out.append("本次没有可引用的持久化证据。");
            return;
        }
        int index = 0;
        for (EvidenceReference reference : evidence) {
            index++;
            out.append(index).append(". ")
                .append(StringUtils.isBlank(reference.title()) ? "证据" : reference.title())
                .append("（").append(reference.evidenceCode()).append("）");
            if (StringUtils.isNotBlank(reference.summary())) {
                out.append("：").append(reference.summary());
            }
            out.append('\n');
        }
        out.setLength(out.length() - 1);
    }

    /**
     * 结论边界：限制说明与数据质量详情放在回答底部，不干扰结论主体。
     */
    private static void appendBounds(StringBuilder out, FaultExecutionResult execution) {
        DiagnosisResult result = execution.diagnosisResult();
        out.append("\n\n## 结论边界\n\n");
        Set<String> bounds = new LinkedHashSet<>();
        if (result.partial()) {
            bounds.add("本次结果为降级结果，请结合边界说明谨慎处理。");
        }
        bounds.addAll(result.limitations());
        bounds.addAll(execution.limitations());
        if (bounds.isEmpty()) {
            out.append("- 无");
        } else {
            for (String bound : bounds) {
                out.append("- ").append(bound).append('\n');
            }
            out.setLength(out.length() - 1);
        }
        out.append("\n\n数据质量详情：").append(dataQualityText(result.dataQuality()));
    }

    /**
     * 模型不可用或未通过校验时的确定性“代码说明与建议”内容，只使用服务端事实。
     */
    static String deterministicMiddle(FaultExecutionResult execution) {
        DiagnosisResult result = execution.diagnosisResult();
        StringBuilder out = new StringBuilder("## 代码说明\n\n");
        List<CandidateFault> candidates = result.candidateFaults();
        if (candidates.isEmpty()) {
            out.append("本次观测没有需要说明的故障码或报警码。");
        } else {
            for (CandidateFault candidate : candidates) {
                appendCandidateExplanation(out, candidate);
            }
            out.setLength(out.length() - 1);
            out.append("\n知识库内容仅为资料解释，不能替代对本设备实际参数的核对。");
        }
        out.append("\n\n## 建议\n\n");
        List<String> recommendations = result.recommendations();
        if (recommendations.isEmpty()) {
            out.append("请结合后续运行数据持续观察。");
        } else {
            int index = 0;
            for (String recommendation : recommendations) {
                index++;
                out.append(index).append(". ").append(recommendation).append('\n');
            }
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static void appendCandidateExplanation(StringBuilder out, CandidateFault candidate) {
        String term = candidate.codeType().term();
        String code = candidate.faultCode();
        switch (candidate.knowledgeStatus()) {
            case MATCHED -> out.append("- ").append(code).append(" 是").append(term)
                .append("码，已匹配手册资料：").append(sourceDocuments(candidate.knowledgeEvidence()))
                .append("。资料说明仅供参考，不代表本设备已确认根因。").append('\n');
            case NOT_FOUND -> out.append("- 未找到 ").append(code).append("（").append(term).append("码）的知识依据。").append('\n');
            case SKIPPED -> out.append("- Agent 未绑定知识库，无法提供 ").append(code).append(" 的资料说明。").append('\n');
            case FAILED -> out.append("- ").append(code).append(" 的知识查询失败，请稍后重试。").append('\n');
        }
    }

    private static String sourceDocuments(List<FaultKnowledgeEvidence> evidence) {
        Set<String> documents = new LinkedHashSet<>();
        if (evidence != null) {
            for (FaultKnowledgeEvidence item : evidence) {
                if (item == null) {
                    continue;
                }
                if (StringUtils.isNotBlank(item.sourceDocument())) {
                    documents.add(item.sourceDocument());
                } else if (item.documentId() != null) {
                    documents.add(String.valueOf(item.documentId()));
                }
            }
        }
        return documents.isEmpty() ? "未知来源" : String.join("、", documents);
    }

    static String dataQualityText(DataQualitySummary quality) {
        return quality == null ? "无数据质量摘要"
            : "原始记录" + quality.rawRecordCount() + "条，有效记录" + quality.validRecordCount()
                + "条，重复" + quality.duplicateCount() + "条，无效时间" + quality.invalidTimeCount()
                + "条，缺口" + quality.gapCount() + "个，完整度" + quality.completeness()
                + "，数据" + (quality.sufficient() ? "充足" : "不足");
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "无" : TIME_FORMATTER.format(value);
    }
}
