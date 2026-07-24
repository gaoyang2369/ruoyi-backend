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

    private boolean enabled = true;

    private int defaultWindowMinutes = 30;

    private int maxWindowMinutes = 120;

    private int nominalSamplingSeconds = 1;

    private double completenessThreshold = 0.8D;

    private int createTimeBufferSeconds = 10;

    private String timezone = "Asia/Shanghai";

    /**
     * 当前部署允许诊断的设备资产编码。
     */
    private List<String> allowedAssets = new ArrayList<>();

}
