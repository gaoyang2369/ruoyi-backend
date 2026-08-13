package org.ruoyi.service.fault;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.entity.fault.OperationReportSnapshot;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.report.DiagnosisSummary;
import org.ruoyi.fault.report.OperationReportResult;
import org.ruoyi.fault.report.ReportHealthStatus;
import org.ruoyi.mapper.fault.OperationReportSnapshotMapper;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class OperationReportSnapshotServiceTest {

    @Mock
    private OperationReportSnapshotMapper mapper;

    private OperationReportSnapshotService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(OperationReportSnapshot.class) == null) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
            assistant.setCurrentNamespace("operationReportSnapshotTest");
            TableInfoHelper.initTableInfo(assistant, OperationReportSnapshot.class);
        }
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new OperationReportSnapshotService(mapper, objectMapper);
    }

    @Test
    void savesAndRestoresTheCompleteOperationReportSnapshot() {
        OperationReportResult report = report("RP-100");
        when(mapper.insert(any(OperationReportSnapshot.class))).thenReturn(1);

        service.save(report, 9L, 7L, "tenant-a");

        ArgumentCaptor<OperationReportSnapshot> entity = ArgumentCaptor.forClass(OperationReportSnapshot.class);
        verify(mapper).insert(entity.capture());
        assertEquals("RP-100", entity.getValue().getReportCode());
        assertEquals(9L, entity.getValue().getSessionId());
        assertEquals(7L, entity.getValue().getUserId());
        assertEquals("tenant-a", entity.getValue().getTenantId());
        assertTrue(entity.getValue().getReportJson().contains("\"reportId\":\"RP-100\""));
        assertTrue(entity.getValue().getReportJson().contains("\"sourceDocuments\":[\"manual.pdf\"]"));
        assertTrue(entity.getValue().getReportJson().contains("\"executiveSummary\":\"模型归纳内容\""));

        when(mapper.selectOne(any())).thenReturn(entity.getValue());
        OperationReportResult restored = service.get("RP-100", 7L, "tenant-a");
        assertEquals(report, restored);
    }

    @Test
    void restoresLegacyPlainTextNarrativeSnapshot() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode json = objectMapper.valueToTree(report("RP-LEGACY"));
        json.put("narrative", "旧版模型归纳");
        OperationReportResult restored = objectMapper.treeToValue(json, OperationReportResult.class);

        assertEquals("旧版模型归纳", restored.narrative().executiveSummary());
    }

    @Test
    void detailQueryAlwaysIncludesReportUserAndTenant() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.get("RP-200", 8L, "tenant-b"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<OperationReportSnapshot>> query =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        query.getValue().getCustomSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("RP-200"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(8L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("tenant-b"));
    }

    @Test
    void duplicateReportCodeCannotOverwriteAnExistingSnapshot() {
        when(mapper.insert(any(OperationReportSnapshot.class)))
            .thenThrow(new DuplicateKeyException("duplicate"));

        ServiceException error = assertThrows(ServiceException.class,
            () -> service.save(report("RP-DUP"), 9L, 7L, "tenant-a"));

        assertEquals("运行报告编号已存在", error.getMessage());
    }

    private static OperationReportResult report(String reportCode) {
        LocalDateTime start = LocalDateTime.of(2026, 8, 13, 10, 0);
        LocalDateTime end = start.plusMinutes(30);
        OperationReportResult base = OperationReportResult.fromSources(
            reportCode, "设备A", "INV-A", start, end, end.plusSeconds(1),
            ReportHealthStatus.UNKNOWN,
            new OperationReportResult.Summary("数据不足，无法确认设备状态。", List.of(), List.of(), false),
            null, null, null, null);
        DiagnosisSummary diagnosis = new DiagnosisSummary(DiagnosisStatus.DATA_INSUFFICIENT,
            List.of(), List.of(), List.of(), true, List.of(),
            List.of(new DiagnosisSummary.CodeKnowledgeSummary("A07089", FaultCodeType.ALARM,
                KnowledgeLookupStatus.MATCHED, List.of("manual.pdf"))));
        return new OperationReportResult(base.metadata(), base.asset(), base.period(), base.periodStatus(),
            base.currentStatus(), base.summary(), base.dataQuality(), base.metricUnits(), base.dataCompleteness(),
            base.metrics(), base.trends(), base.events(), base.statusTimeline(), diagnosis, base.recommendations(),
            base.evidence(), new OperationReportResult.ReportNarrative("模型归纳内容", null, null, List.of(), null), base.limitations(), base.diagnosisDetail());
    }
}
