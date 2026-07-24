package org.ruoyi.service.chat.impl.provider;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.observability.MyChatModelListener;
import org.ruoyi.service.chat.AbstractChatService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.net.http.HttpClient;
import java.util.List;

/**
 * 自定义 API 服务调用
 *
 * 适用于 OpenAI 兼容接口或仅通过通用 HTTP 协议接入的第三方大模型服务。
 * 通过模型配置中的 apiHost / apiKey / modelName 即可复用，不需要再写死具体供应商。
 *
 * @author better
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomApiServiceImpl implements AbstractChatService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);

    @Override
    public StreamingChatModel buildStreamingChatModel(ChatModelVo chatModelVo, ChatRequest chatRequest) {
        return OpenAiStreamingChatModel.builder()
            .baseUrl(normalizeBaseUrl(chatModelVo.getApiHost()))
            .apiKey(defaultIfBlank(chatModelVo.getApiKey(), "EMPTY"))
            .modelName(chatModelVo.getModelName())
            .httpClientBuilder(http11ClientBuilder())
            .timeout(DEFAULT_TIMEOUT)
            .listeners(List.of(new MyChatModelListener()))
            .returnThinking(chatRequest.getEnableThinking())
            .build();
    }

    @Override
    public ChatModel buildChatModel(ChatModelVo chatModelVo) {
        return OpenAiChatModel.builder()
            .baseUrl(normalizeBaseUrl(chatModelVo.getApiHost()))
            .apiKey(defaultIfBlank(chatModelVo.getApiKey(), "EMPTY"))
            .modelName(chatModelVo.getModelName())
            .httpClientBuilder(http11ClientBuilder())
            .timeout(DEFAULT_TIMEOUT)
            .build();
    }

    @Override
    public String getProviderName() {
        return ChatModeType.CUSTOM_API.getCode();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (StrUtil.isBlank(baseUrl)) {
            throw new IllegalArgumentException("自定义API的请求地址(apiHost)不能为空");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    /**
     * vLLM 的 Uvicorn 服务不支持 JDK HttpClient 在明文 HTTP 下默认尝试的 h2c 升级。
     * 固定 HTTP/1.1，避免 Upgrade: h2c 导致服务端丢弃 chunked 请求体并返回 400。
     */
    private JdkHttpClientBuilder http11ClientBuilder() {
        return new JdkHttpClientBuilder()
            .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1))
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(DEFAULT_TIMEOUT);
    }
}
