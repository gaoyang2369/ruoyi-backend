package org.ruoyi.fault.evidence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

/** 一次独立故障诊断案例。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fd_diagnosis_case")
public class DiagnosisCaseEntity extends TenantEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseCode;
    private Long sessionId;
    private Long agentId;
    private Long userId;
    private String assetCode;
    private String question;
    private LocalDateTime queryStartTime;
    private LocalDateTime queryEndTime;
    private String status;
    private String conclusionJson;
    private String rootHash;
    private Integer evidenceCount;
    private String errorMessage;
}
