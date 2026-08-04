package org.ruoyi.fault.telemetry.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运行报告级别的统计量，由遥测分析器与 {@link TelemetryStatistics} 同一次确定性遍历产出。
 * <p>
 * 峰值时间为“最高值出现在什么时刻”提供运维依据；代码出现情况按 G120 归类口径统计，
 * 与故障码提取保持一致。
 *
 * @param maxMotorTempAt 电机温度最高值的业务时间；窗口内无有效电机温度时为 null
 * @param maxMotorLoadRateAt 电机负载率最高值的业务时间；窗口内无有效负载率时为 null
 * @param faultCodeOccurrences 故障码出现情况，按首次出现时间排序
 * @param alarmCodeOccurrences 报警码出现情况，按首次出现时间排序
 */
public record OperationStatistics(
    LocalDateTime maxMotorTempAt,
    LocalDateTime maxMotorLoadRateAt,
    List<CodeOccurrence> faultCodeOccurrences,
    List<CodeOccurrence> alarmCodeOccurrences
) {

    public OperationStatistics {
        faultCodeOccurrences = faultCodeOccurrences == null ? List.of() : List.copyOf(faultCodeOccurrences);
        alarmCodeOccurrences = alarmCodeOccurrences == null ? List.of() : List.copyOf(alarmCodeOccurrences);
    }

    public static OperationStatistics empty() {
        return new OperationStatistics(null, null, List.of(), List.of());
    }

}
