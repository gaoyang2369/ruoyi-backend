package org.ruoyi.fault.telemetry.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行遥测数据。
 */
@Data
@TableName("real_data")
public class RealDataEntity {

    @TableId
    private Long id;

    private String timestamp;
    private String deviceName;
    private String inverterName;
    private String date;
    private String time;

    private String status;
    private String faultCode;
    private String alarmCode;

    private Float dcVoltage;
    private Float speedSetpoint;
    private Float speedActual;
    private Float currentActual;
    private Float torqueSetpoint;
    private Float torqueActual;

    private Float airIntakeTemp;
    private Float motorTemp;
    private Float inverterTemp;
    private Float actualPower;

    private Float inverterRadiatorTemp;
    private Float inverterLoadRate;
    private Float motorLoadRate;

    private LocalDateTime createTime;

}
