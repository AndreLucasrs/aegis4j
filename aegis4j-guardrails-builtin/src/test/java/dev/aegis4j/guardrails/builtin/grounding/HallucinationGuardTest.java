package dev.aegis4j.guardrails.builtin.grounding;

import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.testkit.FakeProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HallucinationGuardTest {

    private static final List<RetrievedChunk> CONTEXT_CHUNKS =
            List.of(new RetrievedChunk("Paris is the capital of France.", "doc-1", 0.9, Map.of()));

    private static GuardContext ctxWithChunks(List<RetrievedChunk> chunks) {
        return new GuardContext("req-1", "user-1", Map.of(GuardContext.RETRIEVED_CHUNKS_KEY, chunks));
    }

    private static GuardContext ctxWithoutChunks() {
        return new GuardContext("req-1", "user-1", Map.of());
    }

    @Test
    void passesWithoutCallingJudgeWhenNoRetrievedChunks() {
        FakeProvider judge = FakeProvider.withId("judge").respondingWith("VERDICT: UNGROUNDED\nCONFIDENCE: 1.0\nREASON: irrelevant");
        HallucinationGuard guard = new HallucinationGuard(judge, "judge-model");

        GuardResult result = guard.checkOutput(ctxWithoutChunks(), "Paris is the capital of France.");

        assertThat(result).isInstanceOf(GuardResult.Pass.class);
        assertThat(judge.receivedRequests()).isEmpty();
    }

    @Test
    void passesWhenJudgeSaysGrounded() {
        FakeProvider judge = FakeProvider.withId("judge")
                .respondingWith("VERDICT: GROUNDED\nCONFIDENCE: 0.95\nREASON: matches context");
        HallucinationGuard guard = new HallucinationGuard(judge, "judge-model");

        GuardResult result = guard.checkOutput(ctxWithChunks(CONTEXT_CHUNKS), "Paris is the capital of France.");

        assertThat(result).isInstanceOf(GuardResult.Pass.class);
        assertThat(judge.lastRequest().model()).isEqualTo("judge-model");
        assertThat(judge.lastRequest().messages().toString())
                .contains("Paris is the capital of France.")
                .contains("RETRIEVED CONTEXT")
                .contains("ASSISTANT RESPONSE");
    }

    @Test
    void warnModeAnnotatesInsteadOfBlockingByDefault() {
        FakeProvider judge = FakeProvider.withId("judge")
                .respondingWith("VERDICT: UNGROUNDED\nCONFIDENCE: 0.9\nREASON: invents the population figure");
        HallucinationGuard guard = new HallucinationGuard(judge, "judge-model");

        GuardResult result = guard.checkOutput(ctxWithChunks(CONTEXT_CHUNKS), "Paris has 40 million people.");

        assertThat(result).isInstanceOf(GuardResult.Modify.class);
        GuardResult.Modify modify = (GuardResult.Modify) result;
        assertThat(modify.reasonCode()).isEqualTo("ungrounded-response");
        assertThat(modify.sanitizedText())
                .startsWith("Paris has 40 million people.")
                .contains("invents the population figure");
    }

    @Test
    void strictModeBlocksUngroundedResponse() {
        FakeProvider judge = FakeProvider.withId("judge")
                .respondingWith("VERDICT: UNGROUNDED\nCONFIDENCE: 0.9\nREASON: invents the population figure");
        HallucinationGuard guard = HallucinationGuard.strict(judge, "judge-model");

        GuardResult result = guard.checkOutput(ctxWithChunks(CONTEXT_CHUNKS), "Paris has 40 million people.");

        assertThat(result).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("ungrounded-response");
    }

    @Test
    void lowConfidenceUngroundedVerdictIsTreatedAsInconclusive() {
        FakeProvider judge = FakeProvider.withId("judge")
                .respondingWith("VERDICT: UNGROUNDED\nCONFIDENCE: 0.1\nREASON: not sure");
        HallucinationGuard guard = HallucinationGuard.strict(judge, "judge-model");

        GuardResult result = guard.checkOutput(ctxWithChunks(CONTEXT_CHUNKS), "Paris has 40 million people.");

        assertThat(result).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void unparseableJudgeResponseFailsOpen() {
        FakeProvider judge = FakeProvider.withId("judge").respondingWith("I cannot answer that.");
        HallucinationGuard guard = HallucinationGuard.strict(judge, "judge-model");

        GuardResult result = guard.checkOutput(ctxWithChunks(CONTEXT_CHUNKS), "Paris has 40 million people.");

        assertThat(result).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void checkInputAlwaysPasses() {
        FakeProvider judge = FakeProvider.withId("judge").respondingWith("VERDICT: GROUNDED\nCONFIDENCE: 1.0\nREASON: n/a");
        HallucinationGuard guard = new HallucinationGuard(judge, "judge-model");

        GuardResult result = guard.checkInput(ctxWithChunks(CONTEXT_CHUNKS), "some user input");

        assertThat(result).isInstanceOf(GuardResult.Pass.class);
        assertThat(judge.receivedRequests()).isEmpty();
    }
}
