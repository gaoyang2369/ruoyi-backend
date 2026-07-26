package org.ruoyi.fault.evidence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

/** 一次诊断过程中的执行步骤。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fd_diagnosis_step")
public class DiagnosisStepEntity extends TenantEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private Integer stepNo;
    private String stepType;
    private String status;
    private String inputJson;
    private String outputSummaryJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
