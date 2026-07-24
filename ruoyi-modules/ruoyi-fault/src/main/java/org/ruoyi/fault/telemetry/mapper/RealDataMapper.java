package org.ruoyi.fault.telemetry.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * real_data 固定诊断查询。
 */
@Mapper
public interface RealDataMapper extends BaseMapperPlus<RealDataEntity, RealDataEntity> {

    /**
     * 仅按设备、逆变器和 create_time 的固定条件读取遥测数据。
     */
    @Select("""
        SELECT id, `timestamp`, device_name, inverter_name, `date`, `time`,
               status, fault_code, alarm_code,
               dc_voltage, speed_setpoint, speed_actual, current_actual, torque_setpoint, torque_actual,
               air_intake_temp, motor_temp, inverter_temp, actual_power,
               inverter_radiator_temp, inverter_load_rate, motor_load_rate, create_time
        FROM real_data
        WHERE device_name = #{deviceName}
          AND inverter_name = #{inverterName}
          AND create_time >= #{queryStart}
          AND create_time < #{queryEnd}
        ORDER BY create_time ASC, id ASC
        """)
    List<RealDataEntity> selectTelemetry(@Param("deviceName") String deviceName,
                                         @Param("inverterName") String inverterName,
                                         @Param("queryStart") LocalDateTime queryStart,
                                         @Param("queryEnd") LocalDateTime queryEnd);

}
