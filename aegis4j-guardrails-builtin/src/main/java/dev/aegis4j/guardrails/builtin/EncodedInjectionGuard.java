package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catches prompt injection that is hidden behind an encoding or obfuscation,
 * which {@link PromptInjectionGuard} cannot see because its regexes only run
 * over the raw text: {@code aWdub3JlIHByZXZpb3VzIGluc3RydWN0aW9ucw==}, the same
 * sentence in Morse, hex, binary, percent-encoding, ROT13, reversed text,
 * invisible Unicode tag characters ("ASCII smuggling"), full-width or
 * Cyrillic-lookalike letters, leetspeak, spaced-out letters, and layered
 * combinations of those (Base64 of Morse of ...).
 *
 * <p>How it works: the input is repeatedly de-obfuscated (breadth-first, up to
 * {@code maxDepth} layers) and every resulting view is matched against the same
 * heuristics as {@link PromptInjectionGuard} (by default {@link
 * PromptInjectionGuard#DEFAULT_PATTERNS}). Work is bounded by {@code maxViews}
 * and {@code maxTotalChars}; if an input needs more than that to explore it
 * fails <em>closed</em> (blocked), since an attacker could otherwise pad the
 * request with decoy blobs until the guard gives up and lets it through.
 *
 * <p>{@link #strict} mode additionally blocks any input that contains a
 * decodable text payload at all (e.g. a readable Base64 or Morse blob), even
 * when the decoded text matches no known pattern. That closes the gap of
 * phrasings the regexes do not know, at the price of rejecting legitimate
 * messages that carry encoded text on purpose.
 *
 * <p><b>Like {@link PromptInjectionGuard}, this is a heuristic, not a security
 * boundary.</b> It cannot decode a scheme it does not know (a custom cipher, a
 * word-substitution code, an encoding the model is merely told how to undo in
 * the prompt itself), and it inherits the paraphrasing blind spot of the
 * underlying patterns. Pair it with system-prompt hardening, least-privilege
 * tools, and output validation.
 *
 * <p>As with the other guards, the block message never echoes the input, the
 * decoded text, or the matched pattern.
 */
public final class EncodedInjectionGuard extends GuardAdapter {

    public static final int DEFAULT_MAX_DEPTH = 3;
    public static final int DEFAULT_MAX_VIEWS = 64;
    public static final int DEFAULT_MAX_TOTAL_CHARS = 1_000_000;

    private final List<Pattern> patterns;
    private final int maxDepth;
    private final int maxViews;
    private final int maxTotalChars;
    private final boolean strict;

    public EncodedInjectionGuard(List<Pattern> patterns, int maxDepth, int maxViews, int maxTotalChars, boolean strict) {
        if (maxDepth < 1 || maxViews < 1 || maxTotalChars < 1) {
            throw new IllegalArgumentException("maxDepth, maxViews and maxTotalChars must be positive");
        }
        this.patterns = List.copyOf(patterns);
        this.maxDepth = maxDepth;
        this.maxViews = maxViews;
        this.maxTotalChars = maxTotalChars;
        this.strict = strict;
    }

    /** Default heuristics, default limits; blocks only when a decoded view matches a known injection pattern. */
    public static EncodedInjectionGuard defaultPatterns() {
        return new EncodedInjectionGuard(PromptInjectionGuard.DEFAULT_PATTERNS,
                DEFAULT_MAX_DEPTH, DEFAULT_MAX_VIEWS, DEFAULT_MAX_TOTAL_CHARS, false);
    }

    /** Like {@link #defaultPatterns()} but also blocks any input carrying a decodable text payload. */
    public static EncodedInjectionGuard strict() {
        return new EncodedInjectionGuard(PromptInjectionGuard.DEFAULT_PATTERNS,
                DEFAULT_MAX_DEPTH, DEFAULT_MAX_VIEWS, DEFAULT_MAX_TOTAL_CHARS, true);
    }

    /** Uses the given patterns (e.g. {@link PromptInjectionGuard#DEFAULT_PATTERNS} plus organization-specific ones). */
    public static EncodedInjectionGuard of(List<Pattern> patterns) {
        return new EncodedInjectionGuard(patterns, DEFAULT_MAX_DEPTH, DEFAULT_MAX_VIEWS, DEFAULT_MAX_TOTAL_CHARS, false);
    }

    @Override
    public String id() {
        return "encoded-prompt-injection";
    }

    @Override
    public GuardResult checkInput(GuardContext ctx, String text) {
        if (text == null) {
            return GuardResult.pass();
        }
        try {
            return inspect(text);
        } catch (StackOverflowError | OutOfMemoryError e) {
            // Hostile input that blows the decoder's resources must never read as "clean".
            return GuardResult.block("encoding-budget-exceeded", "Input is too heavily encoded to inspect");
        }
    }

    private GuardResult inspect(String text) {
        if (matchesAnyPattern(text)) {
            return GuardResult.block("prompt-injection-suspected",
                    "Input matched a known prompt-injection pattern");
        }

        // Breadth-first over successive decodings.
        Deque<Node> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        seen.add(text);
        queue.add(new Node(text, 0, true));
        int views = 1;
        long totalChars = text.length();

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.depth() >= maxDepth) {
                continue;
            }
            for (PayloadDecoder.Derived derived : PayloadDecoder.derive(node.text(), node.respell())) {
                if (!seen.add(derived.text())) {
                    continue;
                }
                if (derived.decodedPayload() && strict) {
                    return GuardResult.block("encoded-payload-detected",
                            "Input contains an encoded text payload");
                }
                if (matchesAnyPattern(derived.text())) {
                    return GuardResult.block("encoded-prompt-injection-suspected",
                            "Input matched a known prompt-injection pattern after decoding");
                }
                views++;
                totalChars += derived.text().length();
                if (views > maxViews || totalChars > maxTotalChars) {
                    return GuardResult.block("encoding-budget-exceeded",
                            "Input is too heavily encoded to inspect");
                }
                // A re-spelling is not re-spelled again (that would multiply views for no gain),
                // but may still hide an encoded payload, so it keeps being decoded.
                queue.add(new Node(derived.text(), node.depth() + 1, derived.decodedPayload()));
            }
        }
        return GuardResult.pass();
    }

    private boolean matchesAnyPattern(String candidate) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(candidate).find()) {
                return true;
            }
        }
        return false;
    }

    private record Node(String text, int depth, boolean respell) {
    }
}
