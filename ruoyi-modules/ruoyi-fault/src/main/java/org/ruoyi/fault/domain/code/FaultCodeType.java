package org.ruoyi.fault.domain.code;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SINAMICS G120 代码类型的确定性分类。
 * <p>
 * 分类口径：{@code F} + 数字为故障码，{@code A} + 数字为报警码，裸 {@code 0} 或空白为无代码，
 * 其余格式一律为未知。未知格式不升级为故障，也不进入报警列表。
 * <p>
 * 当前只支持 G120 场景，不抽象多厂商规则；以后接入其他厂商时按设备类型选择对应分类器。
 */
public enum FaultCodeType {

    /** F + 数字：故障码。 */
    FAULT,
    /** A + 数字：报警码。 */
    ALARM,
    /** 裸 0 或空白：没有代码。 */
    NONE,
    /** 其他格式：未识别代码，不升级为故障。 */
    UNKNOWN;

    private static final Pattern FAULT_PATTERN = Pattern.compile("F\\d+");
    private static final Pattern ALARM_PATTERN = Pattern.compile("A\\d+");

    /**
     * 按 G120 代码前缀归类。不抛异常；null、空白和裸 0 均返回 {@link #NONE}。
     */
    public static FaultCodeType classify(String code) {
        if (code == null) {
            return NONE;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || "0".equals(normalized)) {
            return NONE;
        }
        if (FAULT_PATTERN.matcher(normalized).matches()) {
            return FAULT;
        }
        if (ALARM_PATTERN.matcher(normalized).matches()) {
            return ALARM;
        }
        return UNKNOWN;
    }

    public static boolean isFault(String code) {
        return classify(code) == FAULT;
    }

    public static boolean isAlarm(String code) {
        return classify(code) == ALARM;
    }

    /** 面向用户的代码类型表述：报警称“报警”，其余显式代码称“故障”。 */
    public String term() {
        return this == ALARM ? "报警" : "故障";
    }
}
