package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenRouterAnalysisServiceTest {

    private MockRestServiceServer mockServer;
    private AnalysisResponseParser parser;
    private OpenRouterAnalysisService svc;

    private static final String MODEL = "meta-llama/llama-3.3-70b-instruct:free";
    private static final String OK_BODY = """
            {"choices":[{"message":{"content":"{\\"raw\\":\\"json\\"}"}}]}
            """;

    @BeforeEach
    void setUp() {
        parser = mock(AnalysisResponseParser.class);
        AnalysisPrompt prompt = mock(AnalysisPrompt.class);
        when(prompt.systemPrompt()).thenReturn("system");
        when(prompt.userMessage(any())).thenReturn("user: listing");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        mockServer = MockRestServiceServer.bindTo(builder).build();

        svc = new OpenRouterAnalysisService(prompt, parser, builder, MODEL);
    }

    private AnalysisResult dummyResult() {
        var extracted = new ExtractedData(null, null, null, null, null, null, null, null, null, null, null, null, null);
        var scores = new CategoryScores(70, 70, 70, 70, 70);
        var verdict = new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzic");
        var meta = new AnalysisMeta("openrouter", MODEL, 200L, Instant.now());
        return new AnalysisResult(extracted, List.of(), List.of(), List.of(), scores, verdict, meta);
    }

    @Test
    void happyPath_onePost_returnsResult() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var expected = dummyResult();
        when(parser.parse(any(), eq("openrouter"), eq(MODEL), anyLong())).thenReturn(expected);

        var result = svc.analyze("listing text");

        assertThat(result).isEqualTo(expected);
        assertThat(result.meta().provider()).isEqualTo("openrouter");
        mockServer.verify();
    }

    @Test
    void firstPost503_secondPost200_returnsResult() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andRespond(withServerError());
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var expected = dummyResult();
        when(parser.parse(any(), any(), any(), anyLong())).thenReturn(expected);

        var result = svc.analyze("listing text");

        assertThat(result).isEqualTo(expected);
        mockServer.verify();
    }

    @Test
    void bothPosts503_throwsLlmCallException() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andRespond(withServerError());
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class);
        mockServer.verify();
    }

    @Test
    void parserThrowsSchemaException_propagatesWithoutRetry() {
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        when(parser.parse(any(), any(), any(), anyLong()))
                .thenThrow(new LlmResponseSchemaException("bad schema", "root"));

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmResponseSchemaException.class);
        // Only one call — schema exception should NOT trigger retry
        mockServer.verify();
    }
}
