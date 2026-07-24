package org.ruoyi.fault.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 故障诊断的受控查询配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fault.diagnosis")
public class FaultDiagnosisProperties {

    /** 是否启用确定性故障诊断。 */
    private boolean enabled = true;

    /** 上层未指定时间窗时可采用的默认分钟数；固定查询接口本身仍要求显式起止时间。 */
    private int defaultWindowMinutes = 30;

    /** 单次诊断允许查询的最大分钟数。 */
    private int maxWindowMinutes = 120;

    /** 设备的标称采样周期，用于理论采样数和缺口计算。 */
    private int nominalSamplingSeconds = 1;

    /** 完整度达到该阈值时，数据质量才标记为 sufficient。 */
    private double completenessThreshold = 0.8D;

    /** create_time 数据库粗筛在精确窗口前后扩展的秒数。 */
    private int createTimeBufferSeconds = 10;

    /** 业务观测时间所属时区；禁止回退到 JVM 默认时区。 */
    private String timezone = "Asia/Shanghai";

    /**
     * 当前部署允许诊断的设备资产编码。
     */
    private List<String> allowedAssets = new ArrayList<>();

}
