package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BedrockClaudeAnalysisServiceTest {

    private BedrockRuntimeClient client;
    private AnalysisResponseParser parser;
    private AnalysisPrompt prompt;
    private BedrockClaudeAnalysisService svc;

    private static final String MODEL_ID = "eu.anthropic.claude-haiku-4-5-20251001-v1:0";

    @BeforeEach
    void setUp() {
        client = mock(BedrockRuntimeClient.class);
        parser = mock(AnalysisResponseParser.class);
        prompt = mock(AnalysisPrompt.class);
        when(prompt.systemPrompt()).thenReturn("system");
        when(prompt.userMessage(any())).thenReturn("user: listing");
        svc = new BedrockClaudeAnalysisService(prompt, parser, client, MODEL_ID);
    }

    private ConverseResponse okResponse(String text) {
        var contentBlock = ContentBlock.fromText(text);
        var message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(contentBlock)
                .build();
        var output = ConverseOutput.fromMessage(message);
        return ConverseResponse.builder()
                .output(output)
                .stopReason(StopReason.END_TURN)
                .build();
    }

    private AnalysisResult dummyResult() {
        var extracted = new ExtractedData(null, null, null, null, null, null, null, null, null, null, null, null, null);
        var scores = new CategoryScores(70, 70, 70, 70, 70);
        var verdict = new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić");
        var meta = new AnalysisMeta("bedrock", MODEL_ID, 500L, Instant.now());
        return new AnalysisResult(extracted, List.of(), List.of(), List.of(), scores, verdict, meta);
    }

    @Test
    void happyPath_callsClientOnce_returnsResult() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(okResponse("{\"raw\":\"text\"}"));
        var expected = dummyResult();
        when(parser.parse(any(), eq("bedrock"), eq(MODEL_ID), anyLong())).thenReturn(expected);

        var result = svc.analyze("listing text");

        assertThat(result).isEqualTo(expected);
        assertThat(result.meta().provider()).isEqualTo("bedrock");
        verify(client, times(1)).converse(any(ConverseRequest.class));
    }

    @Test
    void firstCallThrottled_secondSucceeds_returnsResult() {
        var throttle = ThrottlingException.builder().message("throttled").build();
        when(client.converse(any(ConverseRequest.class)))
                .thenThrow(throttle)
                .thenReturn(okResponse("{\"raw\":\"text\"}"));
        var expected = dummyResult();
        when(parser.parse(any(), any(), any(), anyLong())).thenReturn(expected);

        var result = svc.analyze("listing text");

        assertThat(result).isEqualTo(expected);
        verify(client, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void bothCallsThrottled_throwsLlmCallException() {
        var throttle = ThrottlingException.builder().message("throttled").build();
        when(client.converse(any(ConverseRequest.class))).thenThrow(throttle);

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class);
        verify(client, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void parserThrowsSchemaException_propagatesWithoutRetry() {
        when(client.converse(any(ConverseRequest.class))).thenReturn(okResponse("bad json"));
        when(parser.parse(any(), any(), any(), anyLong()))
                .thenThrow(new LlmResponseSchemaException("bad field", "root"));

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmResponseSchemaException.class);
        // client called only once — no retry on schema violations
        verify(client, times(1)).converse(any(ConverseRequest.class));
    }
}
