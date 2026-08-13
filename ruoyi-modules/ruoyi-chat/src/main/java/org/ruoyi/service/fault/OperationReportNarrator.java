package org.ruoyi.service.fault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.service.chat.hermes.HermesChatClient;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesMessage;
import org.ruoyi.service.fault.model.FaultExecutionResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运行报告的可选叙事层。事实由 {@code OperationReportOrchestrator} 一次性生成；
 * Hermes 只接收精简事实并以 REPORT_NARRATION 模式生成 JSON，绝不拥有遥测或工具权限。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationReportNarrator {

    private static final Set<String> NARRATIVE_FIELDS = Set.of("executiveSummary", "operatingFindings",
        "anomalyAnalysis", "recommendations", "riskNotice");
    private static final Set<String> RECOMMENDATION_FIELDS = Set.of("priority", "action", "basis");
    private static final Pattern CODE = Pattern.compile("(?i)(?<![A-Z0-9_-])[FA]\\d{3,}(?![A-Z0-9_-])");
    private static final Pattern EVIDENCE = Pattern.compile("(?i)(?<![A-Z0-9_-])EV-\\d+(?![A-Z0-9_-])");
    private static final Pattern NUMBER = Pattern.compile("\\d");
    private static final Pattern SAFE_IDS = Pattern.compile("(?i)(?<![A-Z0-9_-])(?:[FA]\\d{3,}|EV-\\d+|[PR]\\d+(?:\\.\\d+)?|P[123])(?![A-Z0-9_-])");
    private static final String SYSTEM = """
        你处于内部 REPORT_NARRATION 模式。输入是可信的、已经完成确定性计算的运行报告事实。
        绝对不得调用工具、重新查询设备、推断或修改状态、事件、指标、证据、数据质量或报告结论。
        只根据 facts 中的信息输出一个合法 JSON 对象，不能输出思考过程、Markdown 围栏、解释或工具调用。
        JSON 必须且只能包含：
        {"executiveSummary":"字符串或null","operatingFindings":"字符串或null","anomalyAnalysis":"字符串或null","recommendations":[{"priority":"P1|P2|P3","action":"字符串","basis":"字符串"}],"riskNotice":"字符串或null"}
        仅可引用 allowedEvidenceCodes 中的证据编号。故障码/报警码只能来自 events。p/r 参数只能原样引用 knowledgeFragments。
        不得写入任何数值、单位、健康分或故障概率；运行指标的数值和图表由确定性报告展示。
        手册中的原因只能表述为“可能原因”或“排查方向”，不得写成本设备已确认根因。
        """;

    private final HermesChatClient hermesChatClient;
    private final ObjectMapper objectMapper;

    /** Hermes 不可用、JSON 无法解析或任何边界校验失败时返回 null，调用方保留确定性报告。 */
    public OperationReportResult.ReportNarrative narrate(OperationReportResult report) {
        FaultExecutionResult execution = new FaultExecutionResult(null, report.diagnosisDetail(), Map.of(), report.limitations());
        String body;
        try {
            String facts = objectMapper.writeValueAsString(facts(report, execution));
            body = hermesChatClient.complete(List.of(
                new HermesMessage("system", SYSTEM),
                new HermesMessage("user", facts))).content();
        } catch (Exception ex) {
            log.warn("Hermes 运行报告叙事调用失败，保留确定性报告: reportCode={}, error={}",
                report.metadata().reportId(), ex.toString());
            return null;
        }
        if (StringUtils.isBlank(body)) {
            log.warn("Hermes 运行报告叙事为空，保留确定性报告: reportCode={}", report.metadata().reportId());
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!isFixedShape(root)) {
                log.warn("Hermes 运行报告叙事 JSON 结构非法，保留确定性报告: reportCode={}", report.metadata().reportId());
                return null;
            }
            OperationReportResult.ReportNarrative narrative = objectMapper.treeToValue(root,
                OperationReportResult.ReportNarrative.class);
            if (!valid(narrative, report, execution)) {
                log.warn("Hermes 运行报告叙事越过事实边界，保留确定性报告: reportCode={}", report.metadata().reportId());
                return null;
            }
            return narrative;
        } catch (Exception ex) {
            log.warn("Hermes 运行报告叙事 JSON 解析失败，保留确定性报告: reportCode={}, error={}",
                report.metadata().reportId(), ex.toString());
            return null;
        }
    }

    /** 仅传报告快照中的精简事实；不传原始遥测或完整趋势点。 */
    private static Map<String, Object> facts(OperationReportResult report, FaultExecutionResult execution) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "REPORT_NARRATION");
        out.put("currentStatus", report.currentStatus());
        out.put("periodStatus", report.periodStatus());
        out.put("periodFallback", report.period().fallbackToLatestData());
        out.put("dataQuality", report.dataQuality());
        out.put("events", report.events().stream().map(event -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", event.code()); item.put("type", event.type()); item.put("active", event.active());
            item.put("recoveredAt", event.recoveredAt());
            return item;
        }).toList());
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (OperationReportResult.Metric metric : report.metrics()) {
            String unit = report.metricUnits().get(metric.metricName());
            if (StringUtils.isNotBlank(unit)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", metric.metricName());
                item.put("unit", unit);
                item.put("current", metric.current());
                item.put("average", metric.average());
                item.put("minimum", metric.minimum());
                item.put("maximum", metric.maximum());
                item.put("count", metric.count());
                metrics.add(item);
            }
        }
        out.put("configuredUnitMetricStatistics", metrics);
        StringBuilder knowledge = new StringBuilder();
        FaultAnswerGenerator.appendBoundedKnowledge(knowledge, execution);
        out.put("knowledgeFragments", knowledge.toString());
        out.put("allowedEvidenceCodes", report.evidence().stream().map(OperationReportResult.Evidence::evidenceCode)
            .filter(StringUtils::isNotBlank).toList());
        out.put("limitations", report.limitations());
        return out;
    }

    private static boolean isFixedShape(JsonNode root) {
        if (!root.isObject() || root.size() != NARRATIVE_FIELDS.size()) return false;
        Set<String> names = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(names::add);
        if (!names.equals(NARRATIVE_FIELDS) || !root.path("recommendations").isArray()) return false;
        for (JsonNode recommendation : root.path("recommendations")) {
            if (!recommendation.isObject() || recommendation.size() != RECOMMENDATION_FIELDS.size()) return false;
            Set<String> recommendationNames = new LinkedHashSet<>();
            recommendation.fieldNames().forEachRemaining(recommendationNames::add);
            if (!recommendationNames.equals(RECOMMENDATION_FIELDS)) return false;
        }
        return true;
    }

    private static boolean valid(OperationReportResult.ReportNarrative narrative, OperationReportResult report,
                                 FaultExecutionResult execution) {
        if (narrative == null) return false;
        for (OperationReportResult.NarrativeRecommendation item : narrative.recommendations()) {
            if (item == null || !("P1".equals(item.priority()) || "P2".equals(item.priority()) || "P3".equals(item.priority()))
                || StringUtils.isBlank(item.action()) || StringUtils.isBlank(item.basis())) return false;
        }
        String text = String.join("\n", narrativeText(narrative));
        Set<String> codes = report.events().stream().map(OperationReportResult.Event::code)
            .filter(StringUtils::isNotBlank).collect(java.util.stream.Collectors.toSet());
        Set<String> evidence = report.evidence().stream().map(OperationReportResult.Evidence::evidenceCode)
            .filter(StringUtils::isNotBlank).collect(java.util.stream.Collectors.toSet());
        if (!tokensAllowed(CODE, text, codes) || !tokensAllowed(EVIDENCE, text, evidence)) return false;
        // Reuse the diagnosis knowledge whitelist for p/r tokens; it is built from the bounded knowledge sources.
        if (!TechnicalTokens.valid(text, technicalTokens(execution))) return false;
        String withoutAllowedIds = SAFE_IDS.matcher(text).replaceAll("");
        return !NUMBER.matcher(withoutAllowedIds).find() && !text.contains("健康分") && !text.contains("故障概率");
    }

    private static List<String> narrativeText(OperationReportResult.ReportNarrative narrative) {
        List<String> text = new ArrayList<>();
        for (String value : new String[]{narrative.executiveSummary(), narrative.operatingFindings(),
            narrative.anomalyAnalysis(), narrative.riskNotice()}) if (value != null) text.add(value);
        for (OperationReportResult.NarrativeRecommendation item : narrative.recommendations()) {
            // Priority is constrained separately to P1/P2/P3 and is not report prose.
            // Excluding it prevents its ordinal from being treated as a newly invented telemetry value.
            text.add(item.action()); text.add(item.basis());
        }
        return text;
    }

    private static boolean tokensAllowed(Pattern pattern, String text, Set<String> allowed) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) if (!allowed.contains(matcher.group().toUpperCase(java.util.Locale.ROOT))) return false;
        return true;
    }

    private static Set<String> technicalTokens(FaultExecutionResult execution) {
        Set<String> result = new LinkedHashSet<>();
        execution.knowledgeSources().forEach(source -> {
            if (source != null) result.addAll(TechnicalTokens.tokensIn(source.content()));
        });
        return result;
    }
}
