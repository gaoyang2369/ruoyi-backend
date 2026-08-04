package org.ruoyi.service.fault;

import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.service.fault.model.FaultKnowledgeAnswerDraft;
import org.ruoyi.service.fault.model.FaultKnowledgeFacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 校验并渲染结构化故障知识回答，不处理来源和知识库权限。
 */
final class FaultKnowledgeAnswerRenderer {

    private FaultKnowledgeAnswerRenderer() {
    }

    static boolean valid(FaultKnowledgeAnswerDraft draft, List<FaultKnowledgeFacts> facts,
                         List<String> requestedFaultCodes) {
        if (draft == null || draft.faults().size() != requestedFaultCodes.size()) {
            return false;
        }
        Map<String, FaultKnowledgeFacts> factsByCode = byCode(facts);
        Set<String> seen = new LinkedHashSet<>();
        for (FaultKnowledgeAnswerDraft.FaultAnswer answer : draft.faults()) {
            String code = normalizeCode(answer.faultCode());
            FaultKnowledgeFacts source = factsByCode.get(code);
            if (source == null || !seen.add(code) || StringUtils.isBlank(answer.summary())) {
                return false;
            }
            if (StringUtils.isNotBlank(source.cause()) && answer.causes().isEmpty()) {
                return false;
            }
            boolean sourceHasActions = StringUtils.isNotBlank(source.handling())
                || source.faultValueBranches().stream().anyMatch(item -> !item.actions().isEmpty());
            if (sourceHasActions && answer.actions().isEmpty() && answer.actionsByFaultValue().isEmpty()) {
                return false;
            }
            if (!validBranches(answer, source) || !validTechnicalTokens(answer, source)) {
                return false;
            }
        }
        return seen.equals(new LinkedHashSet<>(requestedFaultCodes.stream()
            .map(FaultKnowledgeAnswerRenderer::normalizeCode)
            .toList()));
    }

    static String renderDraft(FaultKnowledgeAnswerDraft draft, List<FaultKnowledgeFacts> facts) {
        Map<String, FaultKnowledgeFacts> factsByCode = byCode(facts);
        StringBuilder out = new StringBuilder();
        for (FaultKnowledgeAnswerDraft.FaultAnswer answer : draft.faults()) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            FaultKnowledgeFacts source = factsByCode.get(normalizeCode(answer.faultCode()));
            appendTitle(out, source);
            out.append(answer.summary().trim());
            appendListSection(out, "可能原因", answer.causes());
            if (StringUtils.isNotBlank(answer.firstCheck())) {
                out.append("\n\n**先检查什么**\n\n").append(answer.firstCheck().trim());
            }
            appendDraftBranches(out, answer.actionsByFaultValue(),
                source == null ? null : source.faultValueParameter());
            appendListSection(out, "处理建议", answer.actions());
            if (!answer.parameters().isEmpty()) {
                out.append("\n\n**相关参数**\n\n")
                    .append(String.join("、", answer.parameters().stream().map(String::trim).toList()));
            }
            appendListSection(out, "注意事项", answer.notes());
        }
        return out.toString();
    }

    static String renderFallback(List<FaultKnowledgeFacts> facts) {
        StringBuilder out = new StringBuilder();
        for (FaultKnowledgeFacts fact : facts) {
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            appendTitle(out, fact);
            if (StringUtils.isNotBlank(fact.cause())) {
                appendRawSection(out, "可能原因", fact.cause());
            }
            if (StringUtils.isNotBlank(fact.faultValueParameter()) && !fact.faultValueBranches().isEmpty()) {
                out.append("\n\n**先检查什么**\n\n请先读取 `")
                    .append(fact.faultValueParameter())
                    .append("`，再按对应故障值处理。");
            }
            appendFactBranches(out, fact);
            if (StringUtils.isNotBlank(fact.handling())) {
                appendRawSection(out, "处理建议", fact.handling());
            }
            if (StringUtils.isNotBlank(fact.notes())) {
                appendRawSection(out, "注意事项", fact.notes());
            }
            if (StringUtils.isBlank(fact.cause()) && StringUtils.isBlank(fact.handling())
                && fact.faultValueBranches().isEmpty() && StringUtils.isNotBlank(fact.details())) {
                appendRawSection(out, "手册说明", fact.details());
            }
        }
        return out.toString();
    }

    private static boolean validBranches(FaultKnowledgeAnswerDraft.FaultAnswer answer,
                                         FaultKnowledgeFacts source) {
        Map<String, FaultKnowledgeFacts.FaultValueBranch> sourceBranches = new LinkedHashMap<>();
        source.faultValueBranches().forEach(branch -> sourceBranches.put(branch.value(), branch));
        Set<String> sourceValues = sourceBranches.keySet();
        Set<String> answerValues = new LinkedHashSet<>();
        for (FaultKnowledgeAnswerDraft.ActionBranch branch : answer.actionsByFaultValue()) {
            String value = StringUtils.isBlank(branch.faultValue()) ? null : branch.faultValue().trim();
            if (value == null || !answerValues.add(value)) {
                return false;
            }
            FaultKnowledgeFacts.FaultValueBranch sourceBranch = sourceBranches.get(value);
            if (sourceBranch == null
                || StringUtils.isNotBlank(sourceBranch.meaning()) && StringUtils.isBlank(branch.meaning())
                || !sourceBranch.actions().isEmpty() && branch.actions().isEmpty()) {
                return false;
            }
        }
        return sourceValues.isEmpty() ? answerValues.isEmpty() : sourceValues.equals(answerValues);
    }

    private static boolean validTechnicalTokens(FaultKnowledgeAnswerDraft.FaultAnswer answer,
                                                FaultKnowledgeFacts source) {
        Set<String> allowed = SiemensFaultKnowledgeExtractor.technicalTokens(source);
        return TechnicalTokens.valid(answerText(answer), allowed);
    }

    private static String answerText(FaultKnowledgeAnswerDraft.FaultAnswer answer) {
        List<String> values = new ArrayList<>();
        values.add(answer.summary());
        values.addAll(answer.causes());
        values.add(answer.firstCheck());
        values.addAll(answer.actions());
        values.addAll(answer.parameters());
        values.addAll(answer.notes());
        for (FaultKnowledgeAnswerDraft.ActionBranch branch : answer.actionsByFaultValue()) {
            values.add(branch.meaning());
            values.addAll(branch.actions());
        }
        return String.join("\n", values.stream().filter(StringUtils::isNotBlank).toList());
    }

    private static void appendTitle(StringBuilder out, FaultKnowledgeFacts fact) {
        String code = fact == null ? "未知故障码" : fact.faultCode();
        out.append("### ").append(code);
        if (fact != null && StringUtils.isNotBlank(fact.title())) {
            out.append("：").append(fact.title().trim());
        }
        out.append("\n\n");
    }

    private static void appendDraftBranches(StringBuilder out,
                                            List<FaultKnowledgeAnswerDraft.ActionBranch> branches,
                                            String parameter) {
        if (branches.isEmpty()) {
            return;
        }
        out.append("\n\n**按")
            .append(StringUtils.isBlank(parameter) ? "故障值" : " `" + parameter + "`")
            .append("处理**");
        for (FaultKnowledgeAnswerDraft.ActionBranch branch : branches) {
            out.append("\n\n- `").append(branch.faultValue().trim()).append("`");
            if (StringUtils.isNotBlank(branch.meaning())) {
                out.append("：").append(branch.meaning().trim());
            }
            appendNestedActions(out, branch.actions());
        }
    }

    private static void appendFactBranches(StringBuilder out, FaultKnowledgeFacts fact) {
        if (fact.faultValueBranches().isEmpty()) {
            return;
        }
        out.append("\n\n**按")
            .append(StringUtils.isBlank(fact.faultValueParameter())
                ? "故障值" : " `" + fact.faultValueParameter() + "`")
            .append("处理**");
        for (FaultKnowledgeFacts.FaultValueBranch branch : fact.faultValueBranches()) {
            out.append("\n\n- `").append(branch.value()).append("`");
            if (StringUtils.isNotBlank(branch.meaning())) {
                out.append("：").append(oneLine(branch.meaning()));
            }
            appendNestedActions(out, branch.actions());
        }
    }

    private static void appendNestedActions(StringBuilder out, List<String> actions) {
        for (String action : actions) {
            for (String line : action.lines().map(String::trim).filter(StringUtils::isNotBlank).toList()) {
                out.append("\n  - ").append(line.replaceFirst("^[-•]\\s*", ""));
            }
        }
    }

    private static void appendListSection(StringBuilder out, String title, List<String> values) {
        List<String> nonBlank = values.stream().filter(StringUtils::isNotBlank).map(String::trim).toList();
        if (nonBlank.isEmpty()) {
            return;
        }
        out.append("\n\n**").append(title).append("**");
        nonBlank.forEach(item -> out.append("\n\n- ").append(item));
    }

    private static void appendRawSection(StringBuilder out, String title, String value) {
        out.append("\n\n**").append(title).append("**\n\n").append(formatRaw(value));
    }

    private static String formatRaw(String value) {
        List<String> lines = value.lines().map(String::trim).filter(StringUtils::isNotBlank).toList();
        if (lines.size() <= 1) {
            return value.trim();
        }
        return String.join("\n", lines);
    }

    private static String oneLine(String value) {
        return value.lines().map(String::trim).filter(StringUtils::isNotBlank)
            .reduce((left, right) -> left + " " + right).orElse("");
    }

    private static Map<String, FaultKnowledgeFacts> byCode(List<FaultKnowledgeFacts> facts) {
        Map<String, FaultKnowledgeFacts> result = new LinkedHashMap<>();
        for (FaultKnowledgeFacts fact : facts) {
            result.put(normalizeCode(fact.faultCode()), fact);
        }
        return result;
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
