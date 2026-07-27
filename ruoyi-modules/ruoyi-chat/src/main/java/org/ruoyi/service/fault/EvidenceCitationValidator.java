package org.ruoyi.service.fault;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅检查模型引用是否为本次持久化证据编号。 */
@Component
public class EvidenceCitationValidator {
    private static final Pattern EVIDENCE = Pattern.compile("\\[EV-\\d+]");

    public boolean valid(String answer, Set<String> allowedEvidenceCodes, boolean diagnosisExecuted) {
        if (answer == null || answer.isBlank()) return false;
        Set<String> allowed = allowedEvidenceCodes == null ? Set.of() : allowedEvidenceCodes;
        Matcher matcher = EVIDENCE.matcher(answer);
        boolean cited = false;
        while (matcher.find()) {
            cited = true;
            if (!allowed.contains(matcher.group().substring(1, matcher.group().length() - 1))) return false;
        }
        return !diagnosisExecuted || allowed.isEmpty() || cited;
    }
}
