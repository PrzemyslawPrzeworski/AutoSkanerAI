package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.AiAnalysisService;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;

@Service
@Profile("bedrock")
public class BedrockClaudeAnalysisService implements AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BedrockClaudeAnalysisService.class);

    private final AnalysisPrompt prompt;
    private final AnalysisResponseParser parser;
    private final BedrockRuntimeClient client;
    private final String modelId;

    public BedrockClaudeAnalysisService(
            AnalysisPrompt prompt,
            AnalysisResponseParser parser,
            BedrockRuntimeClient client,
            @Value("${llm.bedrock.model-id}") String modelId) {
        this.prompt = prompt;
        this.parser = parser;
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public AnalysisResult analyze(String listingText) {
        long t0 = System.nanoTime();
        ConverseRequest request = buildRequest(listingText);

        ConverseResponse response;
        try {
            response = client.converse(request);
        } catch (ThrottlingException | ServiceUnavailableException e) {
            log.warn("LLM call retry provider={} model={} exceptionClass={} cause={}",
                    "bedrock", modelId, e.getClass().getSimpleName(), e.getMessage());
            try {
                response = client.converse(request);
            } catch (Exception retryEx) {
                log.error("LLM call failed provider={} model={} exceptionClass={} cause={}",
                        "bedrock", modelId, retryEx.getClass().getSimpleName(), retryEx.getMessage());
                throw new LlmCallException("Bedrock call failed after retry", retryEx);
            }
        } catch (Exception e) {
            log.error("LLM call failed provider={} model={} exceptionClass={} cause={}",
                    "bedrock", modelId, e.getClass().getSimpleName(), e.getMessage());
            throw new LlmCallException("Bedrock call failed", e);
        }

        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        TokenUsage usage = response.usage();
        Integer inputTokens = usage != null ? usage.inputTokens() : null;
        Integer outputTokens = usage != null ? usage.outputTokens() : null;
        log.info("LLM call provider={} model={} latencyMs={} inputTokens={} outputTokens={} listingChars={}",
                "bedrock", modelId, latencyMs,
                inputTokens != null ? inputTokens : -1,
                outputTokens != null ? outputTokens : -1,
                listingText.length());

        String rawText = response.output().message().content().get(0).text();
        return parser.parse(rawText, "bedrock", modelId, latencyMs);
    }

    private ConverseRequest buildRequest(String listingText) {
        var systemPromptContent = SystemContentBlock.builder()
                .text(prompt.systemPrompt())
                .build();

        var userMessage = Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(prompt.userMessage(listingText)))
                .build();

        var inferenceConfig = InferenceConfiguration.builder()
                .maxTokens(8192)
                .temperature(0.2f)
                .build();

        return ConverseRequest.builder()
                .modelId(modelId)
                .system(List.of(systemPromptContent))
                .messages(List.of(userMessage))
                .inferenceConfig(inferenceConfig)
                .build();
    }
}
