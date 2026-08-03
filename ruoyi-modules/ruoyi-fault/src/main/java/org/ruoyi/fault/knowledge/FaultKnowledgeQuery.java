package org.ruoyi.fault.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 故障知识查询的业务请求。
 * <p>
 * {@code knowledgeBaseIds} 必须来自 Agent 绑定配置，调用方不能传入 SQL 或任意检索表达式。
 */
public record FaultKnowledgeQuery(
    String faultCode,
    List<Long> knowledgeBaseIds
) {
    private static final Pattern FAULT_CODE_PATTERN = Pattern.compile("[A-Z0-9_-]+");

    public FaultKnowledgeQuery {
        faultCode = normalizeFaultCode(faultCode);
        List<Long> requestedKnowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        knowledgeBaseIds = List.copyOf(new LinkedHashSet<>(requestedKnowledgeBaseIds.stream()
            .filter(java.util.Objects::nonNull).toList()));
        if (knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("Agent未绑定故障知识库");
        }
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** 故障码统一清理并转换为大写，供端口调用方复用。 */
    public static String normalizeFaultCode(String faultCode) {
        String normalized = normalizeRequired(faultCode, "故障码不能为空");
        if ("0".equals(normalized)) {
            throw new IllegalArgumentException("故障码 0 表示无故障，不能查询故障知识");
        }
        if (!FAULT_CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("故障码格式无效");
        }
        return normalized;
    }
}
