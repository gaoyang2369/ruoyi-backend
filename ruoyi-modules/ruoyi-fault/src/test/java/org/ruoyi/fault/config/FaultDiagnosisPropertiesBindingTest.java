package org.ruoyi.fault.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 YAML 中设备表路由的绑定写法。
 * 使用与应用启动相同的 YamlPropertySourceLoader 加载路径：
 * Map 键包含中文等 [a-z0-9-] 之外的字符时必须使用方括号包裹，否则绑定会被静默跳过。
 */
@Tag("dev")
class FaultDiagnosisPropertiesBindingTest {

    @Test
    void bracketedChineseKeysBindIntoDeviceTableRouting() {
        FaultDiagnosisProperties properties = bindFromYaml("""
            fault:
              diagnosis:
                telemetry-table: real_data
                device-telemetry-tables:
                  "[G120电机1]": real_data_01
                  "[G120电机2]": real_data_02
            """);

        assertEquals("real_data_01", properties.getDeviceTelemetryTables().get("G120电机1"));
        assertEquals("real_data_02", properties.getDeviceTelemetryTables().get("G120电机2"));
        assertEquals("real_data", properties.getTelemetryTable());
    }

    @Test
    void unbracketedChineseKeysAreMangledAndUnusable() {
        FaultDiagnosisProperties properties = bindFromYaml("""
            fault:
              diagnosis:
                telemetry-table: real_data
                device-telemetry-tables:
                  G120电机1: real_data_01
            """);

        // 不加方括号时，中文等非法字符会被松弛绑定静默剔除（G120电机1 -> G1201），导致按设备名查不到
        assertTrue(properties.getDeviceTelemetryTables().get("G120电机1") == null,
            "未加方括号的中文键无法按原设备名命中，实际: " + properties.getDeviceTelemetryTables());
    }

    private static FaultDiagnosisProperties bindFromYaml(String yaml) {
        try {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            List<PropertySource<?>> sources = loader.load("test",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
            Binder binder = new Binder(ConfigurationPropertySources.from(sources));
            return binder.bindOrCreate("fault.diagnosis", FaultDiagnosisProperties.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
