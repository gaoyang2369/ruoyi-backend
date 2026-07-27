package org.ruoyi.service.fault.model;

import java.util.LinkedHashSet;
import java.util.List;

/** LLM 仅能提出的请求参数，身份、权限和知识库范围始终由服务端补充。 */
public record FaultRequestPlan(
    List<FaultTaskType> tasks, String deviceName, String inverterName, Integer recentMinutes,
    String startTime, String endTime, List<String> faultCodes, String symptom, List<String> requestedAspects
) {
    public FaultRequestPlan {
        tasks = immutableDistinct(tasks);
        faultCodes = immutableDistinct(faultCodes);
        requestedAspects = immutableDistinct(requestedAspects);
    }

    private static <T> List<T> immutableDistinct(List<T> values) {
        return values == null ? List.of() : List.copyOf(new LinkedHashSet<>(values.stream().filter(java.util.Objects::nonNull).toList()));
    }
}
