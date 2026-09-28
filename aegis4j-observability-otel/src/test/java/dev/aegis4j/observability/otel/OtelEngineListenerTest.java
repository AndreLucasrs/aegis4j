package dev.aegis4j.observability.otel;

import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.Usage;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.rag.Retriever;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.testkit.FakeProvider;
import dev.aegis4j.testkit.FakeRetriever;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelEngineListenerTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @AfterEach
    void tearDown() {
        openTelemetry.getSdkTracerProvider().shutdown();
    }

    @Test
    void createsOneSpanPerChatWithPipelineAttributes() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("hi there");

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listener(new OtelEngineListener(openTelemetry))
                .build();

        CompletionResponse response = engine.chat(ChatRequest.builder()
                .requestId("req-otel-1").providerId("fake").model("m").userInput("hello").build());

        assertThat(response.content()).isEqualTo("hi there");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo(OtelEngineListener.SPAN_NAME);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(span.getAttributes().get(OtelEngineListener.ATTR_REQUEST_ID)).isEqualTo("req-otel-1");
        assertThat(span.getAttributes().get(OtelEngineListener.ATTR_PROVIDER_ID)).isEqualTo("fake");
        assertThat(span.getAttributes().get(OtelEngineListener.ATTR_MODEL)).isEqualTo("m");
        assertThat(span.getAttributes().get(OtelEngineListener.ATTR_FINISH_REASON)).isEqualTo("STOP");
        assertThat(span.getAttributes().get(OtelEngineListener.ATTR_PROVIDER_CALL_DURATION_MS)).isNotNull();

        List<String> eventNames = span.getEvents().stream().map(event -> event.getName()).toList();
        assertThat(eventNames).containsExactly(
                "input_guard.complete", "route.resolved", "provider_call.complete", "output_guard.complete"
        );
    }

    @Test
    void recordsRetrievedChunkCountOnlyWhenRetrieverConfigured() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");
        FakeRetriever retriever = FakeRetriever.withChunks(
                List.of(new RetrievedChunk("fact", "doc-1", 0.9, Map.of())));

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .retriever(retriever)
                .listener(new OtelEngineListener(openTelemetry))
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("tell me").build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(OtelEngineListener.ATTR_RETRIEVED_CHUNK_COUNT)).isEqualTo(1L);
    }

    @Test
    void marksSpanAsErrorWhenPipelineFails() {
        Aegis4jEngine engine = Aegis4jEngine.builder()
                .listener(new OtelEngineListener(openTelemetry))
                .build();

        assertThatThrownBy(() ->
                engine.chat(ChatRequest.builder().userInput("hi").build())
        ).isInstanceOf(IllegalStateException.class);

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    }

    @Test
    void createsOnlySetupSpanForChatStreamWithNoOutputGuardEvent() {
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listener(new OtelEngineListener(openTelemetry))
                .build();

        engine.chatStream(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build())
                .chunks()
                .forEach(chunk -> { });

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        List<String> eventNames = span.getEvents().stream().map(event -> event.getName()).toList();
        assertThat(eventNames).containsExactly("input_guard.complete", "route.resolved");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void survivesAReentrantChatCallOnTheSameThreadWithoutLosingTheOuterSpan() {
        // Nothing in Guard/Provider/Retriever/ModelRouter's contracts rules out a
        // custom component driving another engine.chat() call on the same thread
        // from inside one of the pipeline's phases. Simulate that with a
        // Retriever whose retrieve() itself calls an inner engine's chat().
        OtelEngineListener listener = new OtelEngineListener(openTelemetry);

        FakeProvider innerProvider = FakeProvider.withId("inner").respondingWith("inner response");
        Aegis4jEngine innerEngine = Aegis4jEngine.builder()
                .provider(innerProvider)
                .listener(listener)
                .build();

        Retriever nestingRetriever = (query, topK) -> {
            innerEngine.chat(ChatRequest.builder()
                    .requestId("req-inner").providerId("inner").model("m-inner").userInput("nested").build());
            return List.of();
        };

        FakeProvider outerProvider = FakeProvider.withId("outer").respondingWith("outer response");
        Aegis4jEngine outerEngine = Aegis4jEngine.builder()
                .provider(outerProvider)
                .retriever(nestingRetriever)
                .listener(listener)
                .build();

        CompletionResponse response = outerEngine.chat(ChatRequest.builder()
                .requestId("req-outer").providerId("outer").model("m-outer").userInput("hello").build());

        assertThat(response.content()).isEqualTo("outer response");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData outerSpan = spans.stream()
                .filter(s -> "req-outer".equals(s.getAttributes().get(OtelEngineListener.ATTR_REQUEST_ID)))
                .findFirst().orElseThrow();
        SpanData innerSpan = spans.stream()
                .filter(s -> "req-inner".equals(s.getAttributes().get(OtelEngineListener.ATTR_REQUEST_ID)))
                .findFirst().orElseThrow();

        // The outer span isn't corrupted by the nested call: attributes/events set
        // by callbacks that fire AFTER the nested chat() returns (route resolution,
        // provider call, output guard, chat complete) all landed on the right span.
        assertThat(outerSpan.getAttributes().get(OtelEngineListener.ATTR_PROVIDER_ID)).isEqualTo("outer");
        assertThat(outerSpan.getAttributes().get(OtelEngineListener.ATTR_MODEL)).isEqualTo("m-outer");
        assertThat(outerSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        List<String> outerEvents = outerSpan.getEvents().stream().map(event -> event.getName()).toList();
        assertThat(outerEvents).containsExactly(
                "input_guard.complete", "retrieval.complete", "route.resolved",
                "provider_call.complete", "output_guard.complete"
        );

        assertThat(innerSpan.getAttributes().get(OtelEngineListener.ATTR_PROVIDER_ID)).isEqualTo("inner");
        assertThat(innerSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);

        // Bonus: OpenTelemetry's own Context correctly parents the nested span
        // under the outer one, since the outer span was current while it ran.
        assertThat(innerSpan.getParentSpanId()).isEqualTo(outerSpan.getSpanId());
    }

    @Test
    void recordsUsageAttributesFromProviderResponse() {
        // FakeProvider always returns Usage.UNKNOWN; asserts the listener handles zero-valued usage fine.
        FakeProvider provider = FakeProvider.withId("fake").respondingWith("ok");

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .provider(provider)
                .listener(new OtelEngineListener(openTelemetry))
                .build();

        engine.chat(ChatRequest.builder().providerId("fake").model("m").userInput("hi").build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(OtelEngineListener.ATTR_PROMPT_TOKENS))
                .isEqualTo((long) Usage.UNKNOWN.promptTokens());
    }
}
