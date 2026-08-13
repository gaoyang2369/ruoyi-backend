package org.ruoyi.domain.entity.fault;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/** 已生成运行报告的不可变 JSON 快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fault_operation_report")
public class OperationReportSnapshot extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String reportCode;
    private Long sessionId;
    private Long userId;
    private String deviceName;
    private String inverterName;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String reportStatus;
    private String periodStatus;
    private String currentStatus;
    private String reportJson;
}
