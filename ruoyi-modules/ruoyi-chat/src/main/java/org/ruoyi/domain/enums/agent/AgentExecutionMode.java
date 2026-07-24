package org.ruoyi.domain.enums.agent;

/**
 * 智能体执行方式。
 */
public enum AgentExecutionMode {

    SUPERVISOR,
    DETERMINISTIC;

    public static boolean isSupported(String value) {
        for (AgentExecutionMode mode : values()) {
            if (mode.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

}
