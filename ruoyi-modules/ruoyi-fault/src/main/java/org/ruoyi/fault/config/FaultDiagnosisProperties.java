package org.ruoyi.fault.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 遥测数据默认表名。真实数据接入后保持 real_data 即可。
     */
    private String telemetryTable = "real_data";

    /**
     * 模拟数据阶段的按设备表路由：设备名 -> 专属表名。
     * 命中时优先使用专属表，未命中回退到 {@link #telemetryTable}。
     * 真实数据接入统一表后清空该映射即可完成切换。
     */
    private Map<String, String> deviceTelemetryTables = new LinkedHashMap<>();

    /**
     * 请求窗口内查不到数据时，是否回退到该设备最近可用数据窗口。
     * 模拟数据阶段开启以保证始终有数据可查；可按需在真实数据阶段关闭。
     */
    private boolean latestDataFallbackEnabled = true;

}
