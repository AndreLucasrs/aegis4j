package dev.aegis4j.testkit;

import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.FinishReason;
import dev.aegis4j.api.provider.ModelInfo;
import dev.aegis4j.api.provider.Provider;
import dev.aegis4j.api.provider.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * An in-process {@link Provider} test double — no HTTP involved — for testing
 * the guard/skill/prompt pipeline fast and deterministically. Records every
 * request it receives so tests can assert on what the engine actually sent.
 */
public final class FakeProvider implements Provider {

    private final String id;
    private final List<CompletionRequest> receivedRequests = new ArrayList<>();
    private Function<CompletionRequest, String> responder = request -> "fake response";

    public FakeProvider(String id) {
        this.id = id;
    }

    public static FakeProvider withId(String id) {
        return new FakeProvider(id);
    }

    public FakeProvider respondingWith(String fixedResponse) {
        this.responder = request -> fixedResponse;
        return this;
    }

    public FakeProvider respondingWith(Function<CompletionRequest, String> responder) {
        this.responder = responder;
        return this;
    }

    public List<CompletionRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    public CompletionRequest lastRequest() {
        return receivedRequests.get(receivedRequests.size() - 1);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        receivedRequests.add(request);
        String content = responder.apply(request);
        return new CompletionResponse(
                "fake-" + receivedRequests.size(), request.model(), content,
                FinishReason.STOP, Usage.UNKNOWN, List.of()
        );
    }

    @Override
    public Stream<CompletionChunk> stream(CompletionRequest request) {
        receivedRequests.add(request);
        String content = responder.apply(request);
        return Stream.of(CompletionChunk.ofDelta(content), CompletionChunk.finished());
    }

    @Override
    public List<ModelInfo> listModels() {
        return List.of(new ModelInfo("fake-model", "Fake Model", 8192));
    }
}
