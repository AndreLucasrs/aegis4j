package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic detector for common prompt-injection phrasings on input text:
 * instruction overrides ("ignore previous instructions"), system-prompt
 * exfiltration attempts, forced persona/role overrides ("you are now..."),
 * and well-known jailbreak framings (developer mode, DAN, etc.), covering
 * both Portuguese and English phrasings.
 *
 * <p><b>This is a heuristic, not a security boundary — it does not claim to
 * detect prompt injection perfectly.</b> Regex matching over natural language
 * is trivially evadable (paraphrasing, other languages, splitting the request
 * across turns, encoding tricks) and will also produce false positives on
 * legitimate text that happens to use these phrasings (e.g. a user pasting an
 * article that discusses prompt injection, or genuinely asking "what are your
 * instructions?" in a support context). Treat this guard as one signal among
 * several — pair it with system-prompt hardening, least-privilege tool
 * access, and output validation — never as the sole defense against prompt
 * injection.
 *
 * <p>The default pattern set ({@link #DEFAULT_PATTERNS}, backed by the named
 * {@link InjectionCategory} entries) is a starting point, not a fixed list:
 * callers can pick individual categories ({@link #categories}), extend the
 * defaults with arbitrary regexes ({@link #defaultPatternsPlus}), or replace
 * them entirely ({@link #of}) with organization-specific heuristics.
 *
 * <p>The block message deliberately does not echo which pattern matched or
 * any of the input text: doing so would hand an attacker probing the endpoint
 * a map of exactly what to paraphrase around, defeating the guard. Callers
 * who need the matched category for internal debugging/audit logs should do
 * so out of band (e.g. by wrapping this guard), not through the message that
 * reaches the caller.
 */
public final class PromptInjectionGuard extends GuardAdapter {

    /** Sensible default set of bilingual (pt-BR / en) prompt-injection heuristics, one per {@link InjectionCategory}. */
    public static final List<Pattern> DEFAULT_PATTERNS = Arrays.stream(InjectionCategory.values())
            .map(InjectionCategory::pattern)
            .toList();

    private final List<Pattern> patterns;

    public PromptInjectionGuard(List<Pattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /** Uses only {@link #DEFAULT_PATTERNS} (every {@link InjectionCategory}). */
    public static PromptInjectionGuard defaultPatterns() {
        return new PromptInjectionGuard(DEFAULT_PATTERNS);
    }

    /** Uses only the given named categories — for disabling or auditing specific default heuristics. */
    public static PromptInjectionGuard categories(Set<InjectionCategory> categories) {
        return new PromptInjectionGuard(EnumSet.copyOf(categories).stream().map(InjectionCategory::pattern).toList());
    }

    /** Uses only the given patterns, ignoring {@link #DEFAULT_PATTERNS} entirely. */
    public static PromptInjectionGuard of(Pattern... patterns) {
        return new PromptInjectionGuard(List.of(patterns));
    }

    /** Uses {@link #DEFAULT_PATTERNS} plus the given organization-specific additions. */
    public static PromptInjectionGuard defaultPatternsPlus(Pattern... extra) {
        List<Pattern> combined = new ArrayList<>(DEFAULT_PATTERNS);
        combined.addAll(List.of(extra));
        return new PromptInjectionGuard(combined);
    }

    @Override
    public String id() {
        return "prompt-injection";
    }

    @Override
    public GuardResult checkInput(GuardContext ctx, String text) {
        // No text content to inspect (e.g. a tool-only turn) — nothing to flag.
        if (text == null) {
            return GuardResult.pass();
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                // Deliberately generic: neither the pattern nor the matched
                // substring is included, so an attacker probing the endpoint
                // can't read off what to paraphrase around.
                return GuardResult.block("prompt-injection-suspected",
                        "Input matched a known prompt-injection pattern");
            }
        }
        return GuardResult.pass();
    }
}
