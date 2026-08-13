package org.ruoyi.fault.report;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 遥测字段的稳定语义定义。
 *
 * <p>单位仍从受控配置冻结到报告快照；名称不应由 Prompt、前端或调用方各自翻译。</p>
 */
public final class TelemetryMetricMetadata {

    private static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
        Map.entry("speedActual", "实际转速"),
        Map.entry("motorLoadRate", "电机负载率"),
        Map.entry("currentActual", "实际电流"),
        Map.entry("actualPower", "实际功率"),
        Map.entry("dcVoltage", "直流电压"),
        Map.entry("motorTemp", "电机温度"),
        Map.entry("inverterTemp", "变频器温度"),
        Map.entry("inverterLoadRate", "变频器负载率"));

    private TelemetryMetricMetadata() {
    }

    public static MetricMetadata of(String key, Map<String, String> configuredUnits) {
        String normalizedKey = key == null ? "" : key.trim();
        return new MetricMetadata(normalizedKey,
            DISPLAY_NAMES.getOrDefault(normalizedKey, normalizedKey),
            unitOf(normalizedKey, configuredUnits));
    }

    /** 与既有 metric-units 的 camelCase / kebab-case 兼容，避免引入第二份单位配置。 */
    public static String unitOf(String key, Map<String, String> configuredUnits) {
        if (key == null || configuredUnits == null || configuredUnits.isEmpty()) {
            return null;
        }
        String unit = configuredUnits.get(key);
        if (unit == null || unit.isBlank()) {
            String legacyKey = key.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
            unit = configuredUnits.get(legacyKey);
        }
        return unit == null || unit.isBlank() ? null : unit.trim();
    }

    public record MetricMetadata(String key, String displayName, String unit) {
    }
}
