package org.ruoyi.fault.telemetry.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 遥测数据固定诊断查询。
 * <p>
 * 遥测库独立于主库，统一走 {@code dcma} 数据源；遥测表不含租户字段，
 * 因此显式忽略租户与数据权限拦截。表名由服务端按设备白名单路由后传入，
 * 只接受配置中的受控表名，不接受任何外部输入。
 */
@Mapper
@DS("dcma")
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
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
        FROM ${tableName}
        WHERE device_name = #{deviceName}
          AND inverter_name = #{inverterName}
          AND create_time >= #{queryStart}
          AND create_time < #{queryEnd}
        ORDER BY create_time ASC, id ASC
        """)
    List<RealDataEntity> selectTelemetry(@Param("tableName") String tableName,
                                         @Param("deviceName") String deviceName,
                                         @Param("inverterName") String inverterName,
                                         @Param("queryStart") LocalDateTime queryStart,
                                         @Param("queryEnd") LocalDateTime queryEnd);

    /**
     * 查询该设备在表内最新的入库时间，用于最新数据回退窗口定位。
     */
    @Select("""
        SELECT MAX(create_time)
        FROM ${tableName}
        WHERE device_name = #{deviceName}
          AND inverter_name = #{inverterName}
        """)
    LocalDateTime selectLatestCreateTime(@Param("tableName") String tableName,
                                         @Param("deviceName") String deviceName,
                                         @Param("inverterName") String inverterName);

    /**
     * 查询该设备在表内最早的入库时间，用于约束回退窗口不超出可用数据范围。
     */
    @Select("""
        SELECT MIN(create_time)
        FROM ${tableName}
        WHERE device_name = #{deviceName}
          AND inverter_name = #{inverterName}
        """)
    LocalDateTime selectEarliestCreateTime(@Param("tableName") String tableName,
                                           @Param("deviceName") String deviceName,
                                           @Param("inverterName") String inverterName);

}
