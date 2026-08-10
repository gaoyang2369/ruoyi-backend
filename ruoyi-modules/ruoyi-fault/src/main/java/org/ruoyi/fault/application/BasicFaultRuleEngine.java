package org.ruoyi.fault.application;

import org.ruoyi.fault.domain.enums.DiagnosisStatus;
import org.ruoyi.fault.domain.enums.ObservationType;
import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisDecision;
import org.ruoyi.fault.domain.result.DiagnosisObservation;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于显式故障码、报警码和既有数据质量结论的纯规则引擎。
 * 不以温度、电流或转速自行推断故障，更不确认根因。
 */
@Component
public class BasicFaultRuleEngine implements FaultRuleEngine {

    @Override
    public DiagnosisDecision evaluate(TelemetryQueryResult telemetry, List<CandidateFault> candidateFaults) {
        List<String> faultCodes = telemetry.faultCodes() == null ? List.of() : telemetry.faultCodes();
        List<String> alarmCodes = telemetry.alarmCodes() == null ? List.of() : telemetry.alarmCodes();
        List<DiagnosisObservation> observations = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<String> rationale = new ArrayList<>();

        if (telemetry.quality() == null || !telemetry.quality().sufficient()) {
            observations.add(new DiagnosisObservation("DATA_QUALITY", ObservationType.DATA_QUALITY,
                "当前窗口数据完整率不足", List.of(), List.of()));
            addFaultObservations(observations, faultCodes);
            addAlarmObservations(observations, alarmCodes);
            recommendations.add("请检查采集链路并补充诊断时间窗口数据");
            limitations.add("数据完整率不足，当前结果不能用于确认根因");
            rationale.add("窗口数据完整率不足，诊断优先返回数据不足结论");
            return decision(DiagnosisStatus.DATA_INSUFFICIENT, observations, recommendations, limitations, rationale);
        }
        if (!faultCodes.isEmpty()) {
            addFaultObservations(observations, faultCodes);
            recommendations.add("请根据匹配的故障知识进行人工核验");
            limitations.add("显式故障码不代表根因已经确认");
            rationale.add("检测到 F 类故障码: " + String.join("、", faultCodes));
            return decision(DiagnosisStatus.FAULT_DETECTED, observations, recommendations, limitations, rationale);
        }
        if (!alarmCodes.isEmpty()) {
            addAlarmObservations(observations, alarmCodes);
            recommendations.add("请检查报警时间附近的运行趋势");
            limitations.add("当前诊断主要依赖故障码、报警码和确定性规则，未使用趋势模型推断根因");
            rationale.add("检测到 A 类报警码: " + String.join("、", alarmCodes));
            rationale.add("未检测到 F 类故障码");
            rationale.add("依据规则，A 类报警不升级为故障结论");
            return decision(DiagnosisStatus.WARNING_DETECTED, observations, recommendations, limitations, rationale);
        }
        observations.add(new DiagnosisObservation("NO_EXPLICIT_FAULT", ObservationType.STATUS_EVENT,
            "当前窗口未发现显式故障码或报警码", List.of(), List.of()));
        recommendations.add("请结合后续运行数据持续观察");
        limitations.add("当前窗口未发现显式故障码或报警码不代表设备完全健康");
        limitations.add("当前阶段尚未接入异常检测模型");
        rationale.add("窗口内未检测到 F 类故障码或 A 类报警码");
        return decision(DiagnosisStatus.NO_EXPLICIT_FAULT, observations, recommendations, limitations, rationale);
    }

    private static DiagnosisDecision decision(DiagnosisStatus status, List<DiagnosisObservation> observations,
                                              List<String> recommendations, List<String> limitations,
                                              List<String> rationale) {
        observations.add(new DiagnosisObservation("RULE_DECISION:" + status.name(), ObservationType.RULE_DECISION,
            "依据确定性规则得出诊断结论: " + status.name(), List.of(), List.of()));
        return new DiagnosisDecision(status, observations, recommendations, limitations, rationale);
    }

    private static void addFaultObservations(List<DiagnosisObservation> observations, List<String> faultCodes) {
        for (String faultCode : faultCodes) {
            if (faultCode != null && !faultCode.isBlank()) {
                observations.add(new DiagnosisObservation("FAULT_CODE:" + faultCode.trim(), ObservationType.FAULT_CODE,
                    "观测到显式故障码: " + faultCode.trim(), List.of(faultCode.trim()), List.of()));
            }
        }
    }

    private static void addAlarmObservations(List<DiagnosisObservation> observations, List<String> alarmCodes) {
        for (String alarmCode : alarmCodes) {
            if (alarmCode != null && !alarmCode.isBlank()) {
                observations.add(new DiagnosisObservation("ALARM_CODE:" + alarmCode.trim(), ObservationType.ALARM_CODE,
                    "观测到报警码: " + alarmCode.trim(), List.of(alarmCode.trim()), List.of()));
            }
        }
    }
}
