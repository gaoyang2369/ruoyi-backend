package org.ruoyi.domain.enums.agent;

/**
 * 智能体场景编码。
 */
public enum AgentScenarioCode {

    GENERAL_CHAT,
    FAULT_DIAGNOSIS;

    public static boolean isSupported(String value) {
        for (AgentScenarioCode code : values()) {
            if (code.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

}
