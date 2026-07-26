package org.ruoyi.fault.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 故障知识查询的业务请求。
 * <p>
 * {@code knowledgeBaseIds} 必须来自 Agent 绑定配置，调用方不能传入 SQL 或任意检索表达式。
 */
public record FaultKnowledgeQuery(
    String faultCode,
    String deviceModel,
    List<Long> knowledgeBaseIds
) {
    public FaultKnowledgeQuery {
        faultCode = normalizeRequired(faultCode, "故障码不能为空");
        deviceModel = normalizeOptional(deviceModel);
        List<Long> requestedKnowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        knowledgeBaseIds = List.copyOf(new LinkedHashSet<>(requestedKnowledgeBaseIds.stream()
            .filter(java.util.Objects::nonNull).toList()));
        if (knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("Agent未绑定故障知识库");
        }
    }

    /** 现有 RAG 服务接收的受控检索词。 */
    public String retrievalQuery() {
        return deviceModel == null ? faultCode : deviceModel + " " + faultCode;
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
