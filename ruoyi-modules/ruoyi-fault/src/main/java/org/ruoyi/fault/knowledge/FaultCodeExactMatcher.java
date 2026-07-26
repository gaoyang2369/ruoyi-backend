package org.ruoyi.fault.knowledge;

import java.util.regex.Pattern;

/**
 * 故障码边界匹配工具，避免 F99999 被 F99999A 之类的相邻编码误命中。
 */
public final class FaultCodeExactMatcher {

    private FaultCodeExactMatcher() {
    }

    public static boolean matches(String content, String faultCode) {
        if (content == null || content.isBlank() || faultCode == null || faultCode.isBlank()) {
            return false;
        }
        String normalizedFaultCode = faultCode.trim().toUpperCase(java.util.Locale.ROOT);
        Pattern pattern = Pattern.compile("(?<![A-Z0-9_-])" + Pattern.quote(normalizedFaultCode) + "(?![A-Z0-9_-])",
            Pattern.CASE_INSENSITIVE);
        return pattern.matcher(content).find();
    }
}
