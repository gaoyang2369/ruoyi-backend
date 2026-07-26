package org.ruoyi.fault.application;

import org.ruoyi.fault.domain.result.CandidateFault;
import org.ruoyi.fault.domain.result.DiagnosisDecision;
import org.ruoyi.fault.telemetry.model.TelemetryQueryResult;

import java.util.List;

/** 不访问外部系统的确定性诊断规则接口。 */
public interface FaultRuleEngine {

    DiagnosisDecision evaluate(TelemetryQueryResult telemetry, List<CandidateFault> candidateFaults);
}
