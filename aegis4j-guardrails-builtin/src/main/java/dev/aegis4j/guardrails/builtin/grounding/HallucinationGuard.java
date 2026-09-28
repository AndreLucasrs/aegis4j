package dev.aegis4j.guardrails.builtin.grounding;

import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.Provider;
import dev.aegis4j.api.rag.RetrievedChunk;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM-as-judge grounding check: asks a {@link Provider} whether an output
 * only makes claims supported by the {@link RetrievedChunk}s the engine
 * resolved for this turn, or whether it invents facts not present in them.
 *
 * <p><b>No retrieved context (no RAG configured, or retrieval returned
 * nothing).</b> This guard has no basis to judge grounding against nothing,
 * so it passes the response through unchanged rather than guessing — it is
 * not this guard's job to opine on non-RAG answers. The judge {@link Provider}
 * is not even called in that case.
 *
 * <p><b>Ungrounded verdict.</b> By default ({@link Mode#WARN}) an ungrounded
 * response is not blocked: a false positive from an LLM judge is a real risk,
 * and rejecting an otherwise legitimate answer on a single judge call is
 * often worse than letting it through with a caveat, so the guard appends a
 * short warning to the text instead ({@code GuardResult.Modify}). Callers
 * that would rather fail closed can opt into {@link Mode#STRICT}, which
 * blocks instead.
 *
 * <p>A verdict below {@link #minConfidenceToFlag} is treated as inconclusive
 * and passed through untouched, regardless of mode — this avoids acting on a
 * judge call the judge itself wasn't sure about.
 *
 * <p>The judge {@link Provider}/model are supplied by the caller and can be
 * the same provider used for the conversation or a separate (e.g. cheaper)
 * one; this guard has no hard-coded dependency on any specific provider.
 */
public final class HallucinationGuard extends GuardAdapter {

    /** How this guard reacts to an ungrounded verdict above {@link #minConfidenceToFlag}. */
    public enum Mode {
        /** Append a warning to the response instead of blocking it (default). */
        WARN,
        /** Block the response outright. */
        STRICT
    }

    static final double DEFAULT_MIN_CONFIDENCE = 0.5;

    private static final Pattern CONFIDENCE_PATTERN =
            Pattern.compile("(?i)CONFIDENCE\\s*:\\s*([0-9]*\\.?[0-9]+)");
    private static final Pattern REASON_PATTERN =
            Pattern.compile("(?i)REASON\\s*:\\s*(.+)");

    private final Provider judgeProvider;
    private final String judgeModel;
    private final Mode mode;
    private final double minConfidenceToFlag;

    public HallucinationGuard(Provider judgeProvider, String judgeModel) {
        this(judgeProvider, judgeModel, Mode.WARN, DEFAULT_MIN_CONFIDENCE);
    }

    public HallucinationGuard(Provider judgeProvider, String judgeModel, Mode mode) {
        this(judgeProvider, judgeModel, mode, DEFAULT_MIN_CONFIDENCE);
    }

    public HallucinationGuard(Provider judgeProvider, String judgeModel, Mode mode, double minConfidenceToFlag) {
        this.judgeProvider = judgeProvider;
        this.judgeModel = judgeModel;
        this.mode = mode;
        this.minConfidenceToFlag = minConfidenceToFlag;
    }

    public static HallucinationGuard warning(Provider judgeProvider, String judgeModel) {
        return new HallucinationGuard(judgeProvider, judgeModel, Mode.WARN);
    }

    public static HallucinationGuard strict(Provider judgeProvider, String judgeModel) {
        return new HallucinationGuard(judgeProvider, judgeModel, Mode.STRICT);
    }

    @Override
    public String id() {
        return "hallucination";
    }

    @Override
    public GuardResult checkOutput(GuardContext ctx, String text) {
        List<RetrievedChunk> chunks = ctx.retrievedChunks();
        if (chunks.isEmpty()) {
            return GuardResult.pass();
        }

        GroundingVerdict verdict = judge(chunks, text);
        if (verdict.grounded() || verdict.confidence() < minConfidenceToFlag) {
            return GuardResult.pass();
        }

        String reason = verdict.reason().isBlank() ? "no reason given by judge" : verdict.reason();
        if (mode == Mode.STRICT) {
            return GuardResult.block("ungrounded-response",
                    "Response appears ungrounded in the retrieved context (confidence "
                            + verdict.confidence() + "): " + reason);
        }

        String annotated = text + "\n\n[aegis4j: this response may include claims not supported by the "
                + "retrieved context - " + reason + "]";
        return GuardResult.modify(annotated, "ungrounded-response");
    }

    private GroundingVerdict judge(List<RetrievedChunk> chunks, String responseText) {
        String context = chunks.stream()
                .map(RetrievedChunk::content)
                .map(content -> content == null ? "" : content)
                .collect(Collectors.joining("\n---\n"));

        String judgePrompt = """
                You are a strict fact-checking judge. Compare the ASSISTANT RESPONSE \
                below against the RETRIEVED CONTEXT it was supposed to be grounded in.

                Decide whether the response only makes claims that are supported by \
                the context (GROUNDED), or whether it asserts facts that are not \
                present in the context (UNGROUNDED).

                Respond in EXACTLY this format and nothing else:
                VERDICT: GROUNDED or UNGROUNDED
                CONFIDENCE: a number from 0.0 to 1.0
                REASON: one short sentence

                RETRIEVED CONTEXT:
                %s

                ASSISTANT RESPONSE:
                %s
                """.formatted(context, responseText);

        CompletionRequest request = CompletionRequest.builder()
                .model(judgeModel)
                .messages(List.of(Message.user(judgePrompt)))
                .temperature(0.0)
                .build();

        CompletionResponse response;
        try {
            response = judgeProvider.complete(request);
        } catch (RuntimeException e) {
            // Same fail-open philosophy as an unparseable verdict: a judge
            // call failing (timeout, rate limit, provider error, ...) must
            // never take down a turn whose primary response already
            // succeeded, and must never be treated as "ungrounded" either.
            return new GroundingVerdict(true, 0.0, "judge provider call failed: " + e.getMessage());
        }
        return parseVerdict(response.content());
    }

    /**
     * Deliberately fails open (treats the response as grounded) whenever the
     * judge didn't follow the requested protocol, rather than risk
     * warning/blocking a legitimate response on a parsing error.
     */
    private GroundingVerdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            return new GroundingVerdict(true, 0.0, "empty judge response");
        }

        String upper = raw.toUpperCase(Locale.ROOT);
        boolean ungrounded = upper.contains("UNGROUNDED");
        boolean grounded = !ungrounded && upper.contains("GROUNDED");
        if (!ungrounded && !grounded) {
            return new GroundingVerdict(true, 0.0, "unparseable judge response");
        }

        // A missing/unparseable CONFIDENCE line is treated the same as
        // "the judge isn't sure" (low, not high, confidence) — consistent
        // with the fully-unparseable-response case above, and the opposite
        // of what a default of 1.0 would do to the confidence-threshold
        // check in checkOutput().
        double confidence = extractConfidence(raw).orElse(0.0);
        String reason = extractReason(raw).orElse("");
        return new GroundingVerdict(!ungrounded, confidence, reason);
    }

    private Optional<Double> extractConfidence(String raw) {
        Matcher matcher = CONFIDENCE_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            double value = Double.parseDouble(matcher.group(1));
            return Optional.of(Math.max(0.0, Math.min(1.0, value)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<String> extractReason(String raw) {
        Matcher matcher = REASON_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1).trim());
    }
}
