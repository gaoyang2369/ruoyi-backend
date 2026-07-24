package org.ruoyi.domain.enums.agent;

/**
 * 智能体场景编码。
 */
public enum AgentScenarioCode {

    GENERAL_CHAT,
    FAULT_DIAGNOSIS;

    /**
     * 校验接口传入的字符串是否为受支持的场景编码。
     */
    public static boolean isSupported(String value) {
        for (AgentScenarioCode code : values()) {
            if (code.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

}
