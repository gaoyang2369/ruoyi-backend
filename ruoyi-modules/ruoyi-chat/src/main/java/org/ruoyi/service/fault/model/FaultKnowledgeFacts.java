package org.ruoyi.service.fault.model;

import java.util.List;

/**
 * 从 Siemens 故障手册条目中确定性提取的可回答事实。
 */
public record FaultKnowledgeFacts(
    String faultCode,
    String title,
    String cause,
    String handling,
    String notes,
    String faultValueParameter,
    List<FaultValueBranch> faultValueBranches,
    String details
) {
    public FaultKnowledgeFacts {
        faultValueBranches = faultValueBranches == null ? List.of() : List.copyOf(faultValueBranches);
    }

    public record FaultValueBranch(String value, String meaning, List<String> actions) {
        public FaultValueBranch {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
