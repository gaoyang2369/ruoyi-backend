package org.ruoyi.service.fault;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Siemens 技术参数 token（pXXXX / rXXXX，可带小数下标）的提取与白名单校验。
 * <p>
 * 全链路共用这一份正则：知识提取、知识回答校验与模型输出安全校验都以它为准，
 * 保证"允许出现的参数"与"能够识别的参数"口径一致。
 */
final class TechnicalTokens {

    private static final Pattern TECHNICAL_TOKEN =
        Pattern.compile("(?i)(?<![A-Z0-9_])([pr]\\d+(?:\\.\\d+)?)(?![A-Z0-9_])");

    private TechnicalTokens() {
    }

    /** 提取文本中出现的全部技术参数 token，统一转小写，保持出现顺序。 */
    static Set<String> tokensIn(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher matcher = TECHNICAL_TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    /** 文本中出现的每个技术参数 token 都必须在白名单内；null 文本视为通过。 */
    static boolean valid(String text, Set<String> allowedTokens) {
        if (text == null) {
            return true;
        }
        Set<String> allowed = allowedTokens == null ? Set.of() : allowedTokens;
        Matcher matcher = TECHNICAL_TOKEN.matcher(text);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
