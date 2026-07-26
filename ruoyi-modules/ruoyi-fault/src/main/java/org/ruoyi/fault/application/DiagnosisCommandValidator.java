package org.ruoyi.fault.application;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.fault.domain.command.DiagnosisCommand;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/** 仅校验诊断命令自身；遥测窗口、资产授权等规则仍由遥测服务负责。 */
@Component
public class DiagnosisCommandValidator {

    public DiagnosisCommand validateAndNormalize(DiagnosisCommand command) {
        if (command == null) {
            throw new ServiceException("诊断请求不能为空");
        }
        String deviceName = normalizeRequired(command.deviceName(), "设备名称不能为空");
        String inverterName = normalizeRequired(command.inverterName(), "逆变器名称不能为空");
        if (command.startTime() == null) {
            throw new ServiceException("诊断开始时间不能为空");
        }
        if (command.endTime() == null) {
            throw new ServiceException("诊断结束时间不能为空");
        }
        if (!command.startTime().isBefore(command.endTime())) {
            throw new ServiceException("诊断开始时间必须早于结束时间");
        }
        if (command.context() == null) {
            throw new ServiceException("诊断请求上下文不能为空");
        }
        return new DiagnosisCommand(deviceName, inverterName, command.startTime(), command.endTime(),
            normalizeOptional(command.symptom()), normalizeKnowledgeBaseIds(command.knowledgeBaseIds()), command.context());
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(message);
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null ? null : value.trim();
    }

    private static List<Long> normalizeKnowledgeBaseIds(List<Long> values) {
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (Long value : values) {
                if (value == null) {
                    throw new ServiceException("知识库ID不能为空");
                }
                if (value <= 0) {
                    throw new ServiceException("知识库ID必须大于0");
                }
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }
}
