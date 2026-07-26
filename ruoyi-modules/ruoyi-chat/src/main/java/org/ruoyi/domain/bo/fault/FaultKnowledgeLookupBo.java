package org.ruoyi.domain.bo.fault;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 故障知识检索接口请求；知识库范围只能从 Agent 绑定配置解析。 */
@Data
public class FaultKnowledgeLookupBo {

    @NotNull(message = "Agent ID不能为空")
    private Long agentId;

    @NotBlank(message = "故障码不能为空")
    private String faultCode;
}
