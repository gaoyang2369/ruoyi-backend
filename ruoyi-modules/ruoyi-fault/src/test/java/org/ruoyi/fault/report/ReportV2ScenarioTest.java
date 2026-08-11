package org.ruoyi.fault.report;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.fault.config.FaultDiagnosisProperties;
import org.ruoyi.fault.domain.code.FaultCodeType;
import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.KnowledgeLookupStatus;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisResult;
import org.ruoyi.fault.domain.result.EvidenceReference;
import org.ruoyi.fault.evidence.enums.EvidenceType;
import org.ruoyi.fault.knowledge.FaultKnowledgeEvidence;
import org.ruoyi.fault.telemetry.analysis.TelemetryDataAnalyzer;
import org.ruoyi.fault.telemetry.analysis.TelemetryObservedAtParser;
import org.ruoyi.fault.telemetry.entity.RealDataEntity;
import org.ruoyi.fault.telemetry.model.TelemetryReportSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Report V2 阶段 C 的可复现端到端场景验证。
 * <p>
 * 每个场景均由固定的内存遥测行经 {@link TelemetryDataAnalyzer#analyzeReport} 生成统计和趋势，
 * 不依赖生产数据库。JSON 和 Markdown golden 文件同时是人工检查样例；如确需重新生成，
 * 显式指定 {@code -DreportV2.sampleOutput=/安全的输出目录}，默认测试不会写文件。
 */
@Tag("dev")
class ReportV2ScenarioTest {

    private static final String DEVICE = "G120-SCENARIO";
    private static final String INVERTER = "INV-SCENARIO";
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 10, 9, 0);
    private static final LocalDateTime END = START.plusMinutes(4);
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 10, 9, 5);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final Map<String, String> UNITS = Map.of(
        "dcVoltage", "V", "currentActual", "A", "speedActual", "rpm", "actualPower", "kW",
        "motorTemp", "℃", "inverterTemp", "℃", "motorLoadRate", "%", "inverterLoadRate", "%");
    private static final ObjectMapper JSON = jsonMapper();

    private TelemetryDataAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        FaultDiagnosisProperties properties = new FaultDiagnosisProperties();
        properties.setNominalSamplingSeconds(60);
        properties.setCompletenessThreshold(0.8D);
        analyzer = new TelemetryDataAnalyzer(new TelemetryObservedAtParser(properties), properties);
    }

    @Test
    void normalRunningScenarioHasStableMetricsAndNoEvents() {
        Scenario scenario = normal();

        assertCompleteScenario(scenario, ReportHealthStatus.NORMAL, DiagnosisStatus.NO_EXPLICIT_FAULT);
        assertTrue(scenario.report().events().isEmpty());
        assertTrue(scenario.report().summary().conclusion().contains("未发现显式故障码或报警码"));
        assertEquals(600D, metric(scenario.report(), "dcVoltage").average());
        assertEquals(4, trend(scenario.report(), "actualPower").points().size());
        assertTrue(scenario.markdown().contains("设备状态：正常"));
    }

    @Test
    void recoveredAlarmScenarioPreservesRecoveryFacts() {
        Scenario scenario = recoveredAlarm();

        assertCompleteScenario(scenario, ReportHealthStatus.ATTENTION, DiagnosisStatus.WARNING_DETECTED);
        assertEvent(scenario, "A07089", false, START.plusMinutes(1), START.plusMinutes(2), START.plusMinutes(3), 2);
        assertTrue(scenario.report().summary().alarmCodes().contains("A07089"));
        assertTrue(scenario.markdown().contains("报警 A07089 · 已恢复"));
    }

    @Test
    void activeAlarmScenarioKeepsActiveFlagAtWindowEnd() {
        Scenario scenario = activeAlarm();

        assertCompleteScenario(scenario, ReportHealthStatus.ATTENTION, DiagnosisStatus.WARNING_DETECTED);
        assertEvent(scenario, "A07089", true, START.plusMinutes(1), START.plusMinutes(3), null, 3);
        assertTrue(scenario.markdown().contains("报警 A07089 · 持续中"));
    }

    @Test
    void existingFaultCodeScenarioProducesFaultReport() {
        Scenario scenario = fault();

        assertCompleteScenario(scenario, ReportHealthStatus.FAULT, DiagnosisStatus.FAULT_DETECTED);
        assertEvent(scenario, "F30005", true, START.plusMinutes(1), START.plusMinutes(3), null, 3);
        assertEquals(List.of("F30005"), scenario.report().diagnosis().faultCodes());
        assertTrue(scenario.report().diagnosis().candidateFaults().stream()
            .anyMatch(candidate -> candidate.faultCode().equals("F30005")
                && candidate.knowledgeStatus() == KnowledgeLookupStatus.MATCHED));
        assertTrue(scenario.markdown().contains("故障 F30005 · 持续中"));
    }

    @Test
    void insufficientDataScenarioDoesNotInventMetricsTrendsOrFaultConclusion() {
        Scenario scenario = insufficientData();

        assertEquals(ReportHealthStatus.UNKNOWN, scenario.report().overallStatus());
        assertEquals(DiagnosisStatus.DATA_INSUFFICIENT, scenario.report().diagnosis().status());
        assertFalse(scenario.report().dataQuality().sufficient());
        assertTrue(scenario.report().metrics().isEmpty());
        assertTrue(scenario.report().trends().isEmpty());
        assertTrue(scenario.report().events().isEmpty());
        assertTrue(scenario.report().diagnosis().faultCodes().isEmpty());
        assertTrue(scenario.report().diagnosis().alarmCodes().isEmpty());
        assertTrue(scenario.report().summary().conclusion().contains("无法确认设备状态"));
        assertTrue(scenario.markdown().contains("当前状态无法确认：窗口内数据缺失或不足。"));
        assertEquals(START, scenario.snapshot().telemetry().startTime());
        assertEquals(END, scenario.snapshot().telemetry().endTime());
        assertMappedDiagnosisFields(scenario.report());
    }

    @Test
    void multipleEventsAndStepChangeScenarioKeepsFactsInSameWindow() {
        Scenario scenario = multipleEventsAndChange();

        assertCompleteScenario(scenario, ReportHealthStatus.FAULT, DiagnosisStatus.FAULT_DETECTED);
        assertEvent(scenario, "A07089", false, START, START.plusMinutes(1), START.plusMinutes(2), 2);
        assertEvent(scenario, "F30005", true, START.plusMinutes(2), START.plusMinutes(3), null, 2);
        OperationReportResult.Metric power = metric(scenario.report(), "actualPower");
        assertEquals(10D, power.minimum());
        assertEquals(37.5D, power.average());
        assertEquals(80D, power.maximum());
        assertEquals(List.of(10D, 20D, 40D, 80D), trend(scenario.report(), "actualPower").points().stream()
            .map(OperationReportResult.TrendPoint::value).toList());
        assertTrue(scenario.markdown().contains("## 运行趋势"));
        assertTrue(scenario.markdown().contains("2026-08-10 09:03:00 80（1 条）"));
    }

    @Test
    void reviewedJsonAndMarkdownSamplesMatchScenarioOutputs() throws IOException {
        Map<String, Scenario> scenarios = scenarios();
        String outputDirectory = System.getProperty("reportV2.sampleOutput");
        if (outputDirectory != null && !outputDirectory.isBlank()) {
            Path directory = Path.of(outputDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            for (Map.Entry<String, Scenario> entry : scenarios.entrySet()) {
                Files.writeString(directory.resolve(entry.getKey() + ".json"), json(entry.getValue().report()), StandardCharsets.UTF_8);
                Files.writeString(directory.resolve(entry.getKey() + ".md"), entry.getValue().markdown(), StandardCharsets.UTF_8);
            }
            return;
        }
        for (Map.Entry<String, Scenario> entry : scenarios.entrySet()) {
            assertEquals(sample(entry.getKey() + ".json"), json(entry.getValue().report()), entry.getKey() + " JSON 样例不一致");
            assertEquals(sample(entry.getKey() + ".md"), entry.getValue().markdown(), entry.getKey() + " Markdown 样例不一致");
        }
    }

    private void assertCompleteScenario(Scenario scenario, ReportHealthStatus health, DiagnosisStatus diagnosisStatus) {
        OperationReportResult report = scenario.report();
        assertEquals(health, report.overallStatus());
        assertEquals(diagnosisStatus, report.diagnosis().status());
        assertTrue(report.dataQuality().sufficient());
        assertFalse(report.summary().conclusion().isBlank());
        assertFalse(report.metrics().isEmpty());
        assertFalse(report.trends().isEmpty());
        assertFalse(report.recommendations().isEmpty());
        assertFalse(report.evidence().isEmpty());
        assertFalse(report.limitations().isEmpty());
        assertMappedDiagnosisFields(report);
        assertEquals(START, report.period().analysisWindowStart());
        assertEquals(END, report.period().analysisWindowEnd());
        assertEquals(START, scenario.snapshot().statistics().windowStart());
        assertEquals(END, scenario.snapshot().statistics().windowEnd());
        assertEquals(START, scenario.snapshot().series().windowStart());
        assertEquals(END, scenario.snapshot().series().windowEnd());
        assertEquals(scenario.snapshot().statistics().dataQuality(), report.dataQuality());
        assertEquals(scenario.snapshot().series().dataQuality(), report.dataQuality());
        assertEquals(8, report.metrics().size());
        assertEquals(4, metric(report, "dcVoltage").count());
        report.trends().forEach(item -> item.points().forEach(point -> {
            assertFalse(point.timestamp().isBefore(START));
            assertTrue(point.timestamp().isBefore(END));
        }));
    }

    private static void assertMappedDiagnosisFields(OperationReportResult report) {
        assertEquals(report.diagnosis().faultCodes(), report.summary().faultCodes());
        assertEquals(report.diagnosis().alarmCodes(), report.summary().alarmCodes());
        assertEquals(report.diagnosis().recommendations(), report.recommendations().stream()
            .map(OperationReportResult.Recommendation::content).toList());
        assertEquals(report.diagnosis().limitations(), report.limitations());
        assertEquals(report.diagnosis().evidenceIndex().size(), report.evidence().size());
    }

    private static void assertEvent(Scenario scenario, String code, boolean active, LocalDateTime firstSeen,
                                    LocalDateTime lastSeen, LocalDateTime recoveredAt, int count) {
        OperationReportResult.Event event = scenario.report().events().stream()
            .filter(item -> code.equals(item.code())).findFirst().orElseThrow();
        assertEquals(active, event.active());
        assertEquals(firstSeen, event.firstSeenAt());
        assertEquals(lastSeen, event.lastSeenAt());
        assertEquals(recoveredAt, event.recoveredAt());
        assertEquals(count, event.occurrenceCount());
    }

    private Scenario normal() {
        return report("normal-running", List.of(
            sample(0, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F),
            sample(1, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F),
            sample(2, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F),
            sample(3, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F)),
            DiagnosisStatus.NO_EXPLICIT_FAULT);
    }

    private Scenario recoveredAlarm() {
        return report("recovered-alarm", List.of(
            sample(0, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F),
            sample(1, "42", "0", "A07089", 601F, 10F, 1450F, 11F, 41F, 35F, 51F, 46F),
            sample(2, "42", "0", "A07089", 602F, 11F, 1450F, 12F, 42F, 36F, 52F, 47F),
            sample(3, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F)),
            DiagnosisStatus.WARNING_DETECTED);
    }

    private Scenario activeAlarm() {
        return report("active-alarm", List.of(
            sample(0, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F),
            sample(1, "42", "0", "A07089", 605F, 12F, 1440F, 12F, 42F, 37F, 55F, 48F),
            sample(2, "42", "0", "A07089", 610F, 14F, 1430F, 14F, 44F, 39F, 60F, 52F),
            sample(3, "42", "0", "A07089", 615F, 16F, 1420F, 16F, 46F, 41F, 65F, 56F)),
            DiagnosisStatus.WARNING_DETECTED);
    }

    private Scenario fault() {
        return report("fault-f30005", List.of(
            sample(0, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F),
            sample(1, "42", "F30005", "0", 590F, 15F, 1400F, 25F, 55F, 45F, 80F, 70F),
            sample(2, "42", "F30005", "0", 580F, 20F, 1350F, 40F, 70F, 55F, 100F, 90F),
            sample(3, "42", "F30005", "0", 570F, 25F, 1300F, 55F, 80F, 60F, 110F, 100F)),
            DiagnosisStatus.FAULT_DETECTED);
    }

    private Scenario insufficientData() {
        return report("insufficient-data", List.of(
            sample(0, "0", "0", "0", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F)),
            DiagnosisStatus.DATA_INSUFFICIENT);
    }

    private Scenario multipleEventsAndChange() {
        return report("multiple-events-change", List.of(
            sample(0, "42", "0", "A07089", 600F, 10F, 1450F, 10F, 40F, 35F, 50F, 45F),
            sample(1, "42", "0", "A07089", 605F, 12F, 1440F, 20F, 45F, 38F, 60F, 52F),
            sample(2, "42", "F30005", "0", 610F, 16F, 1420F, 40F, 55F, 45F, 80F, 70F),
            sample(3, "42", "F30005", "0", 620F, 20F, 1400F, 80F, 70F, 55F, 100F, 90F)),
            DiagnosisStatus.FAULT_DETECTED);
    }

    private Scenario report(String name, List<RealDataEntity> records, DiagnosisStatus status) {
        TelemetryReportSnapshot snapshot = analyzer.analyzeReport(DEVICE, INVERTER, START, END, records,
            List.of("dcVoltage", "currentActual", "speedActual", "actualPower", "motorTemp", "inverterTemp",
                "motorLoadRate", "inverterLoadRate"), 1);
        DiagnosisResult diagnosis = diagnosis(name, status, snapshot);
        ReportHealthStatus health = ReportHealthStatus.fromDiagnosisStatus(status);
        OperationReportResult report = OperationReportResult.fromSources("RP-SC-" + name.toUpperCase(), DEVICE, INVERTER,
            START, END, GENERATED_AT, health, summary(health, diagnosis), snapshot.telemetry(), snapshot.statistics(),
            snapshot.series(), diagnosis);
        return new Scenario(report, snapshot, MarkdownOperationReportRenderer.renderFull(report, null, UNITS));
    }

    private static DiagnosisResult diagnosis(String name, DiagnosisStatus status, TelemetryReportSnapshot snapshot) {
        List<String> faults = status == DiagnosisStatus.DATA_INSUFFICIENT ? List.of() : snapshot.telemetry().faultCodes();
        List<String> alarms = status == DiagnosisStatus.DATA_INSUFFICIENT ? List.of() : snapshot.telemetry().alarmCodes();
        List<CandidateFault> candidates = new java.util.ArrayList<>();
        List<EvidenceReference> evidence = new java.util.ArrayList<>();
        evidence.add(new EvidenceReference(1L, "EV-001", EvidenceType.TELEMETRY, "遥测记录",
            name + "：" + snapshot.telemetry().quality().validRecordCount() + " 条有效记录", true));
        long evidenceId = 2L;
        for (String code : concat(faults, alarms)) {
            FaultCodeType type = FaultCodeType.classify(code);
            candidates.add(new CandidateFault(code, type, KnowledgeLookupStatus.MATCHED,
                List.of(new FaultKnowledgeEvidence(7L, "g120-test-manual", "G120故障手册", code, 0,
                    "项目既有测试夹具中的 " + code + " 条目")), List.of("EV-" + String.format("%03d", evidenceId))));
            evidence.add(new EvidenceReference(evidenceId++, "EV-" + String.format("%03d", evidenceId - 1),
                EvidenceType.KNOWLEDGE, "手册资料", "G120故障手册 " + code + " 条目", true));
        }
        List<String> recommendations = status == DiagnosisStatus.DATA_INSUFFICIENT
            ? List.of("检查数据采集链路与网络状态，补齐数据后重新生成报告。")
            : candidates.isEmpty() ? List.of("保持常规巡检，无需额外处理。")
            : List.of("按既有手册核对 " + candidates.get(0).faultCode() + " 的可能原因与处理步骤。");
        List<String> limitations = List.of("诊断仅依据显式故障码、报警码和既有知识匹配，不推断趋势根因。");
        return new DiagnosisResult("scenario-" + name, status, false, DEVICE, INVERTER, START, END, START, END,
            false, snapshot.telemetry().latestObservedAt(), null, snapshot.telemetry().quality(),
            snapshot.telemetry().statistics(), faults, alarms, snapshot.telemetry().unknownCodes(), List.of(), candidates,
            recommendations, limitations, evidence);
    }

    private static OperationReportResult.Summary summary(ReportHealthStatus health, DiagnosisResult diagnosis) {
        String conclusion = switch (health) {
            case NORMAL -> "报告周期内设备状态：正常。窗口内未发现显式故障码或报警码。";
            case ATTENTION -> "报告周期内设备状态：关注。存在报警码 " + String.join("、", diagnosis.alarmCodes()) + "。";
            case FAULT -> "报告周期内设备状态：故障。检测到故障码 " + String.join("、", diagnosis.faultCodes()) + "。";
            case UNKNOWN -> "报告周期内设备状态：未知。无数据或数据质量不足，无法确认设备状态。";
        };
        return new OperationReportResult.Summary(conclusion, diagnosis.faultCodes(), diagnosis.alarmCodes(),
            diagnosis.status() != DiagnosisStatus.DATA_INSUFFICIENT);
    }

    private static RealDataEntity sample(int minute, String status, String faultCode, String alarmCode,
                                          Float dcVoltage, Float currentActual, Float speedActual, Float actualPower,
                                          Float motorTemp, Float inverterTemp, Float motorLoadRate, Float inverterLoadRate) {
        LocalDateTime observedAt = START.plusMinutes(minute);
        RealDataEntity entity = new RealDataEntity();
        entity.setId((long) minute + 1);
        entity.setTimestamp(TIME.format(observedAt));
        entity.setCreateTime(observedAt.plusSeconds(5));
        entity.setDeviceName(DEVICE);
        entity.setInverterName(INVERTER);
        entity.setStatus(status);
        entity.setFaultCode(faultCode);
        entity.setAlarmCode(alarmCode);
        entity.setDcVoltage(dcVoltage);
        entity.setCurrentActual(currentActual);
        entity.setSpeedActual(speedActual);
        entity.setActualPower(actualPower);
        entity.setMotorTemp(motorTemp);
        entity.setInverterTemp(inverterTemp);
        entity.setMotorLoadRate(motorLoadRate);
        entity.setInverterLoadRate(inverterLoadRate);
        return entity;
    }

    private Map<String, Scenario> scenarios() {
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("normal-running", normal());
        scenarios.put("recovered-alarm", recoveredAlarm());
        scenarios.put("active-alarm", activeAlarm());
        scenarios.put("fault-f30005", fault());
        scenarios.put("insufficient-data", insufficientData());
        scenarios.put("multiple-events-change", multipleEventsAndChange());
        return scenarios;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>(first);
        values.addAll(second);
        return List.copyOf(values);
    }

    private static OperationReportResult.Metric metric(OperationReportResult report, String name) {
        return report.metrics().stream().filter(metric -> name.equals(metric.metricName())).findFirst().orElseThrow();
    }

    private static OperationReportResult.Trend trend(OperationReportResult report, String name) {
        return report.trends().stream().filter(trend -> name.equals(trend.metricName())).findFirst().orElseThrow();
    }

    private static String json(OperationReportResult report) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sample(String name) {
        try (InputStream input = ReportV2ScenarioTest.class.getResourceAsStream("/report-v2-scenarios/" + name)) {
            assertNotNull(input, "缺少场景样例: report-v2-scenarios/" + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing() + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectMapper jsonMapper() {
        JavaTimeModule timeModule = new JavaTimeModule();
        timeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(TIME));
        return new ObjectMapper().registerModule(timeModule)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private record Scenario(OperationReportResult report, TelemetryReportSnapshot snapshot, String markdown) {
    }
}
