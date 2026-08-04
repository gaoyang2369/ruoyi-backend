package org.ruoyi.service.fault;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.knowledge.FaultKnowledgeQuery;
import org.ruoyi.fault.knowledge.FaultKnowledgeResult;
import org.ruoyi.service.fault.model.FaultKnowledgeFacts;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class SiemensFaultKnowledgeExtractorTest {

    @Test
    void extractsF13000LabelsFaultValuesAndActionsWithoutManualNoise() {
        String content = """
            F13000 授权不够
            信息值： %1
            信息类别： 参数设置 / 配置 / 调试过程出错 (18)
            SINAMICS S120/S150
            参数手册，06/2020，6SL3097-5AP00-0RP2
            4 故障和报警
            4.2 故障和报警列表
            原因：
            - 在变频器中使用了需要授权的选件，并且授权不足。
            - 在检测现有授权时出现故障。
            故障值（r0949，十进制）：
            0：
            现有授权不够。
            1：
            没有得到足够的授权，因为具有运行所需授权数据的存储卡被拔掉。
            2：
            没有得到足够的授权，因为存储卡上没有授权数据。
            3：
            没有得到足够的授权，因为许可密钥上有一个校验累积误差。
            4：
            在检测授权时出现了一个内部故障。
            处理：
            故障值 =0 时：
            需要附加的需可权并激活（p9920,p9921）。
            故障值 =1 时：
            在关闭状态下重新插入合适的存储卡。
            故障值 =2 时：
            输入许可密钥并激活（p9920, p9921）。
            故障值 =3 时：
            把输入的许可密钥（p9920）同许可证上的许可密钥作比较。
            重新输入许可密钥并激活（p9920, p9921）。
            故障值 =4 时：
            - 执行上电。
            - 将固件升级到新版本。
            - 联系技术支持。
            注释：
            调试工具的在线模式中会列明变频器上所有需要授权才可以运行的功能。
            2861
            """;
        FaultKnowledgeFacts facts = extract("F13000", content);

        assertEquals("授权不够", facts.title());
        assertEquals("r0949", facts.faultValueParameter());
        assertTrue(facts.cause().contains("使用了需要授权的选件"));
        assertEquals(List.of("0", "1", "2", "3", "4"),
            facts.faultValueBranches().stream().map(FaultKnowledgeFacts.FaultValueBranch::value).toList());
        assertTrue(facts.faultValueBranches().get(3).actions().get(0).contains("p9920"));
        assertEquals(3, facts.faultValueBranches().get(4).actions().size());
        assertTrue(facts.notes().contains("在线模式"));
        String extracted = facts.toString();
        assertFalse(extracted.contains("参数手册"));
        assertFalse(extracted.contains("故障和报警列表"));
        assertFalse(extracted.contains("2861"));
    }

    @Test
    void extractsSimpleF07561Sections() {
        FaultKnowledgeFacts facts = extract("F07561", """
            F07561 驱动编码器多圈线数不是二的幂次方
            原因：参数 p0421 设置错误。
            处理建议：
            检查 p0421 的参数设定。
            重新执行编码器配置。
            """);

        assertEquals("驱动编码器多圈线数不是二的幂次方", facts.title());
        assertEquals("参数 p0421 设置错误。", facts.cause());
        assertTrue(facts.handling().contains("重新执行编码器配置"));
        assertTrue(facts.faultValueBranches().isEmpty());
    }

    private static FaultKnowledgeFacts extract(String code, String content) {
        FaultKnowledgeQuery query = new FaultKnowledgeQuery(code, List.of(21L));
        FaultKnowledgeEvidence evidence =
            new FaultKnowledgeEvidence(21L, "doc", "manual.pdf", "fragment", 1, content);
        return SiemensFaultKnowledgeExtractor.extract(List.of(code),
            Map.of(code, FaultKnowledgeResult.matched(query, List.of(evidence)))).get(0);
    }
}
