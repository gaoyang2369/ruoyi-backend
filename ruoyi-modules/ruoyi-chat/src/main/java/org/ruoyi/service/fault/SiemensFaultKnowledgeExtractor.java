package org.ruoyi.service.fault;

import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.service.fault.model.FaultKnowledgeFacts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Siemens 故障手册标签级提取器，只处理稳定标签，不尝试理解任意厂商文档。
 */
final class SiemensFaultKnowledgeExtractor {
    private static final Pattern LABEL = Pattern.compile(
        "(?m)(?:^|\\n)\\s*(含义|原因|处理建议|处理|注释)\\s*[：:]\\s*");
    private static final Pattern FAULT_VALUE_PARAMETER =
        Pattern.compile("(?i)故障值[^\\n]{0,60}?([rR]\\d+(?:\\.\\d+)?)");
    private static final Pattern VALUE_MEANING =
        Pattern.compile("(?m)^\\s*(\\d+)\\s*[：:]\\s*(.*)$");
    private static final Pattern VALUE_ACTION =
        Pattern.compile("(?im)^\\s*故障值\\s*=\\s*([^\\s时：:]+)\\s*时\\s*[：:]\\s*(.*)$");
    private static final Pattern TECHNICAL_TOKEN =
        Pattern.compile("(?i)(?<![A-Z0-9_])([pr]\\d+(?:\\.\\d+)?)(?![A-Z0-9_])");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\d{3,5}$");
    private static final Pattern CHAPTER_LINE =
        Pattern.compile("^\\d+(?:\\.\\d+)*\\s+故障和报警(?:列表)?$");

    private SiemensFaultKnowledgeExtractor() {
    }

    static List<FaultKnowledgeFacts> extract(List<String> faultCodes,
                                             Map<String, FaultKnowledgeResult> results) {
        List<FaultKnowledgeFacts> facts = new ArrayList<>();
        for (String faultCode : faultCodes) {
            FaultKnowledgeResult result = results.get(faultCode);
            if (result != null && result.status() == FaultKnowledgeResult.Status.MATCHED) {
                facts.add(extract(faultCode, result.evidence()));
            }
        }
        return List.copyOf(facts);
    }

    static Set<String> technicalTokens(FaultKnowledgeFacts facts) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TECHNICAL_TOKEN.matcher(sourceText(facts));
        while (matcher.find()) {
            result.add(matcher.group(1).toLowerCase());
        }
        return Set.copyOf(result);
    }

    private static FaultKnowledgeFacts extract(String faultCode, List<FaultKnowledgeEvidence> evidence) {
        List<ParsedSection> parsed = evidence == null ? List.of() : evidence.stream()
            .filter(item -> item != null && StringUtils.isNotBlank(item.content()))
            .map(item -> parse(faultCode, item.content()))
            .toList();
        String title = firstNonBlank(parsed.stream().map(ParsedSection::title).toList());
        String cause = joinDistinct(parsed.stream().map(ParsedSection::cause).toList());
        String handling = joinDistinct(parsed.stream().map(ParsedSection::handling).toList());
        String notes = joinDistinct(parsed.stream().map(ParsedSection::notes).toList());
        String details = joinDistinct(parsed.stream().map(ParsedSection::details).toList());
        String parameter = firstNonBlank(parsed.stream().map(ParsedSection::faultValueParameter).toList());

        Map<String, MutableBranch> branches = new LinkedHashMap<>();
        for (ParsedSection section : parsed) {
            section.meanings().forEach((value, meaning) ->
                branches.computeIfAbsent(value, ignored -> new MutableBranch()).meaning = meaning);
            section.actions().forEach((value, actions) ->
                branches.computeIfAbsent(value, ignored -> new MutableBranch()).actions.addAll(actions));
        }
        List<FaultKnowledgeFacts.FaultValueBranch> values = branches.entrySet().stream()
            .map(entry -> new FaultKnowledgeFacts.FaultValueBranch(entry.getKey(),
                blankToNull(entry.getValue().meaning), distinct(entry.getValue().actions)))
            .toList();
        return new FaultKnowledgeFacts(faultCode, blankToNull(title), blankToNull(cause),
            blankToNull(handling), blankToNull(notes), blankToNull(parameter), values,
            blankToNull(details));
    }

    private static ParsedSection parse(String faultCode, String content) {
        String normalized = normalize(content);
        List<LabelMatch> labels = new ArrayList<>();
        Matcher labelMatcher = LABEL.matcher(normalized);
        while (labelMatcher.find()) {
            labels.add(new LabelMatch(labelMatcher.group(1), labelMatcher.start(), labelMatcher.end()));
        }

        String title = extractTitle(normalized, faultCode);
        String meaning = section(normalized, labels, "含义");
        if (StringUtils.isNotBlank(meaning)) {
            title = meaning.lines().findFirst().orElse(meaning).trim();
        }
        String causeSection = section(normalized, labels, "原因");
        String handlingSection = firstNonBlank(Arrays.asList(
            section(normalized, labels, "处理建议"),
            section(normalized, labels, "处理")));
        String notes = section(normalized, labels, "注释");

        String parameter = null;
        Matcher parameterMatcher = FAULT_VALUE_PARAMETER.matcher(causeSection == null ? "" : causeSection);
        if (parameterMatcher.find()) {
            parameter = parameterMatcher.group(1).toLowerCase();
        }
        Map<String, String> meanings = parseMeanings(causeSection);
        Map<String, List<String>> actions = parseActions(handlingSection);
        String cause = beforeFaultValues(causeSection, meanings);
        String handling = beforeValueActions(handlingSection);
        String details = labels.isEmpty() ? stripManualNoise(normalized) : null;
        return new ParsedSection(title, cause, handling, notes, parameter, meanings, actions, details);
    }

    private static String section(String content, List<LabelMatch> labels, String name) {
        for (int i = 0; i < labels.size(); i++) {
            LabelMatch current = labels.get(i);
            if (!current.name().equals(name)) {
                continue;
            }
            int end = i + 1 < labels.size() ? labels.get(i + 1).start() : content.length();
            return stripManualNoise(content.substring(current.end(), end));
        }
        return null;
    }

    private static Map<String, String> parseMeanings(String cause) {
        if (StringUtils.isBlank(cause) || !cause.contains("故障值")) {
            return Map.of();
        }
        String values = cause.substring(cause.indexOf("故障值"));
        List<BlockMatch> matches = matches(VALUE_MEANING, values);
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < matches.size(); i++) {
            BlockMatch current = matches.get(i);
            int end = i + 1 < matches.size() ? matches.get(i + 1).start() : values.length();
            String body = joinInline(current.inline(), values.substring(current.end(), end));
            result.put(current.key(), stripManualNoise(body));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> parseActions(String handling) {
        if (StringUtils.isBlank(handling)) {
            return Map.of();
        }
        List<BlockMatch> matches = matches(VALUE_ACTION, handling);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < matches.size(); i++) {
            BlockMatch current = matches.get(i);
            int end = i + 1 < matches.size() ? matches.get(i + 1).start() : handling.length();
            String body = joinInline(current.inline(), handling.substring(current.end(), end));
            result.put(current.key(), actionableLines(stripManualNoise(body)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<BlockMatch> matches(Pattern pattern, String text) {
        List<BlockMatch> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            result.add(new BlockMatch(matcher.group(1).trim(), matcher.group(2).trim(),
                matcher.start(), matcher.end()));
        }
        return result;
    }

    private static String beforeFaultValues(String cause, Map<String, String> meanings) {
        if (StringUtils.isBlank(cause) || meanings.isEmpty()) {
            return cause;
        }
        int marker = cause.indexOf("故障值");
        return stripManualNoise(marker < 0 ? cause : cause.substring(0, marker));
    }

    private static String beforeValueActions(String handling) {
        if (StringUtils.isBlank(handling)) {
            return handling;
        }
        Matcher matcher = VALUE_ACTION.matcher(handling);
        return stripManualNoise(matcher.find() ? handling.substring(0, matcher.start()) : handling);
    }

    private static String extractTitle(String content, String faultCode) {
        Pattern code = Pattern.compile("(?i)(?<![A-Z0-9_-])" + Pattern.quote(faultCode)
            + "(?![A-Z0-9_-])");
        Matcher matcher = code.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        int end = content.indexOf('\n', matcher.end());
        String title = content.substring(matcher.end(), end < 0 ? content.length() : end).trim();
        return title.length() > 120 ? null : title;
    }

    private static List<String> actionableLines(String value) {
        if (StringUtils.isBlank(value)) {
            return List.of();
        }
        List<String> lines = value.lines()
            .map(String::trim)
            .map(line -> line.replaceFirst("^[-•]\\s*", ""))
            .filter(StringUtils::isNotBlank)
            .toList();
        return lines.isEmpty() ? List.of(value.trim()) : distinct(lines);
    }

    private static String stripManualNoise(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return value.lines()
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .filter(line -> !line.startsWith("SINAMICS "))
            .filter(line -> !line.startsWith("参数手册"))
            .filter(line -> !line.equals("S150"))
            .filter(line -> !PAGE_NUMBER.matcher(line).matches())
            .filter(line -> !CHAPTER_LINE.matcher(line).matches())
            .reduce((left, right) -> left + "\n" + right)
            .map(String::trim)
            .orElse(null);
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .replaceAll("[ \\t]+", " ")
            .trim();
    }

    private static String sourceText(FaultKnowledgeFacts facts) {
        StringBuilder out = new StringBuilder();
        append(out, facts.title());
        append(out, facts.cause());
        append(out, facts.handling());
        append(out, facts.notes());
        append(out, facts.details());
        append(out, facts.faultValueParameter());
        for (FaultKnowledgeFacts.FaultValueBranch branch : facts.faultValueBranches()) {
            append(out, branch.meaning());
            branch.actions().forEach(item -> append(out, item));
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String value) {
        if (StringUtils.isNotBlank(value)) {
            out.append(value).append('\n');
        }
    }

    private static String joinInline(String inline, String body) {
        if (StringUtils.isBlank(inline)) {
            return body;
        }
        return inline + (StringUtils.isBlank(body) ? "" : "\n" + body);
    }

    private static String firstNonBlank(List<String> values) {
        return values.stream().filter(StringUtils::isNotBlank).findFirst().orElse(null);
    }

    private static String joinDistinct(List<String> values) {
        return distinct(values).stream().reduce((left, right) -> left + "\n" + right).orElse(null);
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values.stream()
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .toList()));
    }

    private static String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private record LabelMatch(String name, int start, int end) {
    }

    private record BlockMatch(String key, String inline, int start, int end) {
    }

    private record ParsedSection(String title, String cause, String handling, String notes,
                                 String faultValueParameter, Map<String, String> meanings,
                                 Map<String, List<String>> actions, String details) {
    }

    private static final class MutableBranch {
        private String meaning;
        private final List<String> actions = new ArrayList<>();
    }
}
