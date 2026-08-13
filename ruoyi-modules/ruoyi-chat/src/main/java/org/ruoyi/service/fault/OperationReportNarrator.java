package org.ruoyi.service.fault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.service.chat.hermes.HermesChatClient;
import org.ruoyi.service.chat.hermes.HermesChatClient.HermesMessage;
import org.ruoyi.service.fault.model.FaultExecutionResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hermes 的报告叙事适配器；结构化事实、查询和报告调用链均不在这里改变。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationReportNarrator {

    private static final Set<String> NARRATIVE_FIELDS = Set.of("executiveSummary", "operatingFindings",
        "anomalyAnalysis", "recommendations", "riskNotice");
    private static final Set<String> RECOMMENDATION_FIELDS = Set.of("priority", "action", "basis");
    private static final Pattern CODE = Pattern.compile("(?i)(?<![A-Z0-9_-])[FA]\\d{3,}(?![A-Z0-9_-])");
    private static final Pattern EVIDENCE = Pattern.compile("(?i)(?<![A-Z0-9_-])EV-\\d+(?![A-Z0-9_-])");
    private static final Pattern UNSUPPORTED_MEASUREMENT = Pattern.compile(
        "(?i)\\d+(?:\\.\\d+)?\\s*(?:V|A|kW|℃|°C|r/min|伏|安|千瓦|转/分)|(?:电机|逆变器|负载|电流|电压|功率|转速|温度).{0,16}?\\d+(?:\\.\\d+)?\\s*%");
    private static final Pattern HEALTH_OR_PROBABILITY = Pattern.compile("(?:健康分|健康度|健康评分|故障概率|概率).{0,8}?\\d+");
    private static final String SYSTEM = """
        你处于内部 REPORT_NARRATION 模式。只根据可信 facts 输出一个 JSON 对象，不得调用工具、重算或修改事实。
        只允许字段 executiveSummary、operatingFindings、anomalyAnalysis、recommendations、riskNotice；可选字段可缺失或为 null。
        JSON 结构：executiveSummary 为字符串；operatingFindings、anomalyAnalysis 为字符串数组；recommendations 为
        [{priority:"P1|P2|P3",action:"字符串",basis:["证据或事实依据"]}]；riskNotice 为字符串。
        executiveSummary 写 2 至 3 句；operatingFindings 2 至 4 条；anomalyAnalysis 2 至 4 条；recommendations 2 至 4 条且每条必须有依据；riskNotice 1 至 2 句。不得用大量空字段敷衍。
        仅可引用 allowedEvidenceCodes；故障/报警码只能来自 events；p/r 参数必须原样来自 knowledgeFragments。
        禁止健康分、故障概率、未提供的遥测数值或单位。允许正常的序号、时间描述和段落编号。
        手册内容只能表述为“可能原因”或“排查方向”，不能写成已确认根因。不能输出 think、Markdown 围栏或解释。
        """;
    private static final String REPAIR_SYSTEM = """
        你是 REPORT_NARRATION JSON 修复器。将用户给出的原输出仅修复为合法 JSON，不增添事实。
        去除 think、围栏和未知字段；保留允许字段。operatingFindings/anomalyAnalysis 必须为字符串数组，basis 必须为字符串数组。
        只输出 JSON 对象，不能解释。不得加入数值、单位、健康分或故障概率。
        """;

    private final HermesChatClient hermesChatClient;
    private final ObjectMapper objectMapper;

    /** 首次不合法时只补一次 JSON 修复；修复失败即保持确定性报告。 */
    public OperationReportResult.ReportNarrative narrate(OperationReportResult report) {
        FaultExecutionResult execution = new FaultExecutionResult(null, report.diagnosisDetail(), Map.of(), report.limitations());
        String body;
        try {
            body = hermesChatClient.complete(List.of(new HermesMessage("system", SYSTEM),
                new HermesMessage("user", objectMapper.writeValueAsString(facts(report, execution))))).content();
        } catch (Exception ex) {
            reject(report, "EMPTY_RESPONSE", null, ex);
            return null;
        }
        Validation first = parseAndValidate(body, report, execution);
        if (first.narrative() != null) return accept(report, first.narrative());
        reject(report, first.reason(), body, null);
        try {
            String repaired = hermesChatClient.complete(List.of(new HermesMessage("system", REPAIR_SYSTEM),
                new HermesMessage("user", body == null ? "" : body))).content();
            Validation second = parseAndValidate(repaired, report, execution);
            if (second.narrative() != null) return accept(report, second.narrative());
            reject(report, second.reason(), repaired, null);
        } catch (Exception ex) {
            reject(report, "JSON_PARSE_FAILED", null, ex);
        }
        return null;
    }

    private OperationReportResult.ReportNarrative accept(OperationReportResult report,
                                                           OperationReportResult.ReportNarrative narrative) {
        log.info("Hermes 运行报告 narrative accepted: reportCode={}", report.metadata().reportId());
        return narrative;
    }

    private void reject(OperationReportResult report, String reason, String body, Exception ex) {
        String sample = StringUtils.isBlank(body) ? "" : sanitizeForLog(body);
        log.warn("Hermes 运行报告叙事 rejected: reportCode={}, rejectionReason={}, responseSample={}, error={}",
            report.metadata().reportId(), reason, sample, ex == null ? "" : ex.toString());
    }

    private static String sanitizeForLog(String body) {
        String compact = body.replaceAll("(?is)<think>.*?</think>", "").replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    /** 清理常见模型包装，并从说明文字中取得第一个括号平衡的完整 JSON 对象。 */
    static String cleanHermesJson(String body) {
        if (body == null) return "";
        String value = body.replaceAll("(?is)<think>.*?</think>", "").trim()
            .replaceAll("(?is)^```(?:json)?\\s*", "").replaceAll("(?is)\\s*```$", "").trim();
        int start = value.indexOf('{');
        if (start < 0) return value;
        boolean inString = false, escaped = false;
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
            } else if (ch == '"') inString = true;
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return value.substring(start, index + 1).trim();
        }
        return value.substring(start).trim();
    }

    private Validation parseAndValidate(String body, OperationReportResult report, FaultExecutionResult execution) {
        if (StringUtils.isBlank(body)) return Validation.reject("EMPTY_RESPONSE");
        JsonNode root;
        try { root = objectMapper.readTree(cleanHermesJson(body)); }
        catch (Exception ex) { return Validation.reject("JSON_PARSE_FAILED"); }
        String structureReason = structureReason(root);
        if (structureReason != null) return Validation.reject(structureReason);
        OperationReportResult.ReportNarrative narrative = narrativeOf(root);
        String reason = validityReason(narrative, report, execution);
        return reason == null ? Validation.accept(narrative) : Validation.reject(reason);
    }

    private static String structureReason(JsonNode root) {
        if (!root.isObject()) return "INVALID_FIELD_TYPE";
        var names = root.fieldNames();
        while (names.hasNext()) if (!NARRATIVE_FIELDS.contains(names.next())) return "UNKNOWN_FIELD";
        for (String name : List.of("executiveSummary", "riskNotice")) {
            JsonNode value = root.path(name);
            if (!value.isMissingNode() && !value.isNull() && !value.isTextual()) return "INVALID_FIELD_TYPE";
        }
        for (String name : List.of("operatingFindings", "anomalyAnalysis")) {
            JsonNode value = root.path(name);
            if (!value.isMissingNode() && !value.isNull() && !value.isTextual() && !strings(value)) return "INVALID_FIELD_TYPE";
        }
        JsonNode recommendations = root.path("recommendations");
        if (!recommendations.isMissingNode() && !recommendations.isNull() && !recommendations.isArray()) return "INVALID_FIELD_TYPE";
        if (recommendations.isArray()) for (JsonNode item : recommendations) {
            if (!item.isObject()) return "INVALID_FIELD_TYPE";
            var recommendationNames = item.fieldNames();
            while (recommendationNames.hasNext()) if (!RECOMMENDATION_FIELDS.contains(recommendationNames.next())) return "UNKNOWN_FIELD";
            if (!textOrNull(item.path("priority")) || !textOrNull(item.path("action"))) return "INVALID_FIELD_TYPE";
            JsonNode basis = item.path("basis");
            if (!basis.isMissingNode() && !basis.isNull() && !basis.isTextual() && !strings(basis)) return "INVALID_FIELD_TYPE";
        }
        return null;
    }

    private static boolean textOrNull(JsonNode node) { return node.isMissingNode() || node.isNull() || node.isTextual(); }
    private static boolean strings(JsonNode node) { for (JsonNode item : node) if (!item.isTextual()) return false; return true; }

    private static OperationReportResult.ReportNarrative narrativeOf(JsonNode root) {
        List<OperationReportResult.NarrativeRecommendation> recommendations = new ArrayList<>();
        JsonNode entries = root.path("recommendations");
        if (entries.isArray()) for (JsonNode item : entries) recommendations.add(new OperationReportResult.NarrativeRecommendation(
            text(item, "priority"), text(item, "action"), values(item.path("basis"))));
        return new OperationReportResult.ReportNarrative(text(root, "executiveSummary"), values(root.path("operatingFindings")),
            values(root.path("anomalyAnalysis")), recommendations, text(root, "riskNotice"));
    }

    private static String text(JsonNode node, String name) { JsonNode value = node.path(name); return value.isTextual() ? value.asText() : null; }
    private static List<String> values(JsonNode node) {
        if (node.isTextual()) return List.of(node.asText());
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>(); for (JsonNode item : node) result.add(item.asText()); return result;
    }

    private static String validityReason(OperationReportResult.ReportNarrative narrative, OperationReportResult report,
                                         FaultExecutionResult execution) {
        if (narrative == null || (StringUtils.isBlank(narrative.executiveSummary()) && narrative.anomalyAnalysis().isEmpty()
            && narrative.recommendations().isEmpty())) return "EMPTY_RESPONSE";
        for (OperationReportResult.NarrativeRecommendation item : narrative.recommendations()) {
            if (item == null || !("P1".equals(item.priority()) || "P2".equals(item.priority()) || "P3".equals(item.priority())) || StringUtils.isBlank(item.action())
                || item.basis().isEmpty()) return "INVALID_FIELD_TYPE";
        }
        String prose = String.join("\n", narrativeText(narrative));
        Set<String> codes = report.events().stream().map(OperationReportResult.Event::code).filter(StringUtils::isNotBlank)
            .map(value -> value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        Set<String> evidence = report.evidence().stream().map(OperationReportResult.Evidence::evidenceCode).filter(StringUtils::isNotBlank)
            .map(value -> value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (!tokensAllowed(CODE, prose, codes)) return "UNKNOWN_CODE";
        if (!tokensAllowed(EVIDENCE, prose, evidence)) return "UNKNOWN_EVIDENCE";
        if (!TechnicalTokens.valid(prose, technicalTokens(execution))) return "UNKNOWN_PARAMETER";
        if (UNSUPPORTED_MEASUREMENT.matcher(prose).find() || HEALTH_OR_PROBABILITY.matcher(prose).find()) return "UNSUPPORTED_NUMBER";
        return null;
    }

    private static List<String> narrativeText(OperationReportResult.ReportNarrative narrative) {
        List<String> text = new ArrayList<>();
        if (narrative.executiveSummary() != null) text.add(narrative.executiveSummary());
        text.addAll(narrative.operatingFindings()); text.addAll(narrative.anomalyAnalysis());
        if (narrative.riskNotice() != null) text.add(narrative.riskNotice());
        for (OperationReportResult.NarrativeRecommendation item : narrative.recommendations()) { text.add(item.action()); text.addAll(item.basis()); }
        return text;
    }

    private static boolean tokensAllowed(Pattern pattern, String text, Set<String> allowed) {
        Matcher matcher = pattern.matcher(text); while (matcher.find()) if (!allowed.contains(matcher.group().toUpperCase(Locale.ROOT))) return false; return true;
    }
    private static Set<String> technicalTokens(FaultExecutionResult execution) {
        Set<String> result = new LinkedHashSet<>(); execution.knowledgeSources().forEach(source -> { if (source != null) result.addAll(TechnicalTokens.tokensIn(source.content())); }); return result;
    }

    /** 仅传报告快照中的精简事实；不传原始遥测或完整趋势点。 */
    private static Map<String, Object> facts(OperationReportResult report, FaultExecutionResult execution) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "REPORT_NARRATION"); out.put("currentStatus", report.currentStatus()); out.put("periodStatus", report.periodStatus());
        out.put("summary", report.summary());
        out.put("decisionRationale", report.diagnosis() == null ? List.of() : report.diagnosis().decisionRationale());
        out.put("dataQuality", report.dataQuality());
        out.put("events", report.events().stream().map(event -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", event.code()); item.put("type", event.type()); item.put("active", event.active());
            item.put("firstSeenAt", event.firstSeenAt()); item.put("lastSeenAt", event.lastSeenAt()); item.put("recoveredAt", event.recoveredAt());
            return item;
        }).toList());
        StringBuilder knowledge = new StringBuilder(); FaultAnswerGenerator.appendBoundedKnowledge(knowledge, execution); out.put("knowledgeFragments", knowledge.toString());
        out.put("allowedEvidenceCodes", report.evidence().stream().map(OperationReportResult.Evidence::evidenceCode).filter(StringUtils::isNotBlank).toList()); out.put("limitations", report.limitations());
        return out;
    }

    private record Validation(OperationReportResult.ReportNarrative narrative, String reason) {
        static Validation accept(OperationReportResult.ReportNarrative narrative) { return new Validation(narrative, null); }
        static Validation reject(String reason) { return new Validation(null, reason); }
    }
}
