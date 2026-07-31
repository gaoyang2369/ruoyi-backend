package org.ruoyi.service.fault.model;

import java.util.List;

/**
 * 模型生成的结构化知识回答草稿。来源和查询边界不由模型生成。
 */
public record FaultKnowledgeAnswerDraft(List<FaultAnswer> faults) {
    public FaultKnowledgeAnswerDraft {
        faults = faults == null ? List.of() : List.copyOf(faults);
    }

    public record FaultAnswer(
        String faultCode,
        String summary,
        List<String> causes,
        String firstCheck,
        List<ActionBranch> actionsByFaultValue,
        List<String> actions,
        List<String> parameters,
        List<String> notes
    ) {
        public FaultAnswer {
            causes = causes == null ? List.of() : List.copyOf(causes);
            actionsByFaultValue = actionsByFaultValue == null ? List.of() : List.copyOf(actionsByFaultValue);
            actions = actions == null ? List.of() : List.copyOf(actions);
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    public record ActionBranch(String faultValue, String meaning, List<String> actions) {
        public ActionBranch {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
