package org.ruoyi.domain.enums.agent;

/**
 * 智能体执行方式。
 */
public enum AgentExecutionMode {

    SUPERVISOR,
    DETERMINISTIC;

    /**
     * 校验接口传入的字符串是否为受支持的执行方式。
     */
    public static boolean isSupported(String value) {
        for (AgentExecutionMode mode : values()) {
            if (mode.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

}
