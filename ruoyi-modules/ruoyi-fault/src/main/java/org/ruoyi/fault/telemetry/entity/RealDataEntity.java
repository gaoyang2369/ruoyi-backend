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

    /** 数据库主键。 */
    @TableId
    private Long id;

    /** 设备上报的首选业务时间文本。 */
    private String timestamp;

    /** 设备资产名称。 */
    private String deviceName;

    /** 逆变器名称。 */
    private String inverterName;

    /** timestamp 无法解析时使用的日期文本。 */
    private String date;

    /** timestamp 无法解析时使用的时间文本。 */
    private String time;

    /** 设备运行状态。 */
    private String status;

    /** 设备故障码。 */
    private String faultCode;

    /** 设备报警码。 */
    private String alarmCode;

    /** 直流侧电压。 */
    private Float dcVoltage;

    /** 速度设定值。 */
    private Float speedSetpoint;

    /** 实际速度。 */
    private Float speedActual;

    /** 实际电流。 */
    private Float currentActual;

    /** 转矩设定值。 */
    private Float torqueSetpoint;

    /** 实际转矩。 */
    private Float torqueActual;

    /** 进气温度。 */
    private Float airIntakeTemp;

    /** 电机温度。 */
    private Float motorTemp;

    /** 逆变器温度。 */
    private Float inverterTemp;

    /** 实际功率。 */
    private Float actualPower;

    /** 逆变器散热器温度。 */
    private Float inverterRadiatorTemp;

    /** 逆变器负载率。 */
    private Float inverterLoadRate;

    /** 电机负载率。 */
    private Float motorLoadRate;

    /** 数据入库时间，仅用于数据库粗筛和重复记录冲突裁决。 */
    private LocalDateTime createTime;

}
