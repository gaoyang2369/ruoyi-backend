package org.ruoyi.fault.evidence.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.ruoyi.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/** 将证据参与哈希和入库的 JSON 规范化。 */
@Component
public class EvidenceCanonicalJsonWriter {

    private final ObjectMapper objectMapper;

    public EvidenceCanonicalJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS)
            .setDateFormat(new StdDateFormat().withColonInTimeZone(true));

        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(new LocalDateTimeSerializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        this.objectMapper.registerModule(javaTimeModule);
    }

    /**
     * 输出稳定的 UTF-8 JSON 文本。Java 字符串不携带编码，哈希时由 {@link Sha256Hasher} 使用 UTF-8 编码。
     */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ServiceException("证据 JSON 规范化失败");
        }
    }
}
