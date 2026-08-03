package org.ruoyi.service.fault;

import org.ruoyi.service.fault.model.FaultExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只保护故障码/报警码范围和少量高风险表述；不尝试做通用自然语言事实核验。
 * F 类故障码与 A 类报警码都必须来自本次观测或用户查询，不允许模型编造。
 */
@Component
public class FaultAnswerSafetyValidator {
    private static final Pattern CONFIDENCE = Pattern.compile("(?:置信度|可信度|(?:故障)?概率)\\s*(?:为|是|：|:)?\\s*\\d+(?:\\.\\d+)?\\s*%?");
    private static final Pattern F_CODE = Pattern.compile("(?<![A-Z0-9_-])F\\d{3,}(?![A-Z0-9_-])", Pattern.CASE_INSENSITIVE);
    private static final Pattern A_CODE = Pattern.compile("(?<![A-Z0-9_-])A\\d{3,}(?![A-Z0-9_-])", Pattern.CASE_INSENSITIVE);
    private static final String[] OBSERVED_WORDS = {"本次检测到", "本次发现", "遥测出现", "控制器记录到", "当前设备发生"};
    private final EvidenceCitationValidator evidenceCitationValidator;

    public FaultAnswerSafetyValidator(EvidenceCitationValidator evidenceCitationValidator) {
        this.evidenceCitationValidator = evidenceCitationValidator;
    }

    public boolean valid(String answer, FaultExecutionResult execution, boolean diagnosisExecuted) {
        if (!evidenceCitationValidator.valid(answer, execution.allowedEvidenceCodes(), diagnosisExecuted)
            || answer == null || CONFIDENCE.matcher(answer).find()) return false;
        // 纯知识查询没有读取遥测；模型正文只要提及遥测，就交由确定性答案统一说明边界。
        if (!diagnosisExecuted && answer.contains("遥测")) return false;
        Set<String> allowed = new LinkedHashSet<>(execution.observedFaultCodes());
        allowed.addAll(execution.observedAlarmCodes());
        allowed.addAll(execution.queriedOnlyCodes());
        if (!allCodesAllowed(F_CODE.matcher(answer.toUpperCase(Locale.ROOT)), allowed)) return false;
        if (!allCodesAllowed(A_CODE.matcher(answer.toUpperCase(Locale.ROOT)), allowed)) return false;
        for (String section : answer.split("[。；\\n]")) {
            String upper = section.toUpperCase(Locale.ROOT);
            for (String code : execution.queriedOnlyCodes()) {
                if (upper.contains(code) && containsObservedClaim(section) && !isExplicitNegation(upper, code)) return false;
            }
        }
        return true;
    }

    private static boolean allCodesAllowed(Matcher codes, Set<String> allowed) {
        while (codes.find()) if (!allowed.contains(codes.group())) return false;
        return true;
    }

    private static boolean containsObservedClaim(String section) {
        for (String phrase : OBSERVED_WORDS) if (section.contains(phrase)) return true;
        return false;
    }

    private static boolean isExplicitNegation(String section, String code) {
        return section.contains("未检测到 " + code) || section.contains("未检测到" + code)
            || section.contains("未发现 " + code) || section.contains("未发现" + code)
            || section.contains("未出现 " + code) || section.contains("未出现" + code);
    }
}
