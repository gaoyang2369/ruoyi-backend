package org.ruoyi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Hermes OpenAI-compatible chat endpoint configuration. */
@Data
@Component
@ConfigurationProperties(prefix = "hermes")
public class HermesChatProperties {

    /** OpenAI-compatible base URL, normally ending in {@code /v1}. */
    private String baseUrl = "http://127.0.0.1:8642/v1";

    /** Kept only in configuration; never include it in diagnostics or responses. */
    private String apiKey;

    private String model = "fault";
}
