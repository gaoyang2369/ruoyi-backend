package org.ruoyi.fault.evidence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 可独立引用且参与哈希链的诊断证据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fd_evidence")
public class DiagnosisEvidenceEntity extends TenantEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private Long stepId;
    private Integer evidenceSeq;
    private String evidenceCode;
    private String evidenceType;
    private String sourceSystem;
    private String sourceReference;
    private String requestJson;
    private String resultSummaryJson;
    private Integer sourceRecordCount;
    private String sourceDigest;
    private String requestHash;
    private String resultHash;
    private String previousHash;
    private String currentHash;
    private String hashVersion;
    private BigDecimal qualityScore;
    private LocalDateTime collectedAt;
}
