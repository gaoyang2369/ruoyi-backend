package org.ruoyi.fault.domain.result;

import org.ruoyi.fault.evidence.enums.EvidenceType;

/**
 * 对提交五证据服务实际生成的证据编号的最小引用，并携带服务端确定性生成的用户展示信息。
 *
 * @param evidenceId 证据数据库主键，仅供内部审计
 * @param evidenceCode 证据编号（如 EV-001），用于哈希链与用户引用
 * @param evidenceType 证据类型，用于用户展示分类
 * @param title 用户可读标题（如“遥测记录”“手册资料”“判断规则”）
 * @param summary 服务端根据实际证据记录生成的一句话摘要
 * @param userVisible 是否出现在普通用户回答中；内部审计步骤为 false
 */
public record EvidenceReference(
    Long evidenceId,
    String evidenceCode,
    EvidenceType evidenceType,
    String title,
    String summary,
    boolean userVisible
) {

    /** 兼容构造：仅保留编号、不带展示信息的内部审计引用。 */
    public EvidenceReference(Long evidenceId, String evidenceCode) {
        this(evidenceId, evidenceCode, null, null, null, false);
    }
}
