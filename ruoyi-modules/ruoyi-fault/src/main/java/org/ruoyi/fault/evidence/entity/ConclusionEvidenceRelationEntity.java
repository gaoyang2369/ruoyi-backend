package org.ruoyi.fault.evidence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

/** 结论和证据的最小关联记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fd_conclusion_evidence_rel")
public class ConclusionEvidenceRelationEntity extends TenantEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String conclusionCode;
    private Long evidenceId;
    private String relationType;
    private BigDecimal weight;
}
