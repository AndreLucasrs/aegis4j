package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

import java.util.ArrayList;
import java.util.List;
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
 * <p>The default pattern set ({@link #DEFAULT_PATTERNS}) is a starting point,
 * not a fixed list: callers can extend it ({@link #defaultPatternsPlus}) or
 * replace it entirely ({@link #of}) with organization-specific heuristics.
 */
public final class PromptInjectionGuard extends GuardAdapter {

    /** Sensible default set of bilingual (pt-BR / en) prompt-injection heuristics. */
    public static final List<Pattern> DEFAULT_PATTERNS = List.of(
            // "ignore previous instructions" and variants
            Pattern.compile("(?i)ignor[ae]\\s+(todas\\s+)?(as\\s+|suas\\s+)?instru[cç][õo]es\\s+(anterior(es)?|acima|pr[ée]via)"),
            Pattern.compile("(?i)ignore\\s+(all\\s+|your\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions"),
            Pattern.compile("(?i)desconsidere\\s+(as\\s+)?instru[cç][õo]es\\s+(anterior(es)?|acima)"),
            Pattern.compile("(?i)disregard\\s+(all\\s+|your\\s+)?(previous|prior|above)\\s+(instructions|rules|prompt)"),
            Pattern.compile("(?i)esque[cç]a\\s+(todas\\s+)?(as\\s+)?(suas\\s+)?regras"),
            Pattern.compile("(?i)forget\\s+(all\\s+|your\\s+)?(previous\\s+)?(rules|instructions|guidelines)"),
            // system-prompt exfiltration attempts
            Pattern.compile("(?i)revele?\\s+(o\\s+|seu\\s+)?system\\s*prompt"),
            Pattern.compile("(?i)reveal\\s+(your|the)\\s+system\\s*prompt"),
            Pattern.compile("(?i)(mostre|exiba|imprima|repita)\\s+(suas\\s+|as\\s+)?instru[cç][õo]es\\s+(de\\s+sistema|iniciais)"),
            Pattern.compile("(?i)(show|print|repeat)\\s+(me\\s+)?your\\s+(system\\s+)?(prompt|instructions)"),
            Pattern.compile("(?i)what\\s+(are|were)\\s+your\\s+(original\\s+)?instructions"),
            Pattern.compile("(?i)quais\\s+s[aã]o\\s+suas\\s+instru[cç][õo]es\\s+(originais|iniciais|de\\s+sistema)"),
            // forced role/persona overrides
            Pattern.compile("(?i)voc[eê]\\s+agora\\s+[ée]\\s+"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+"),
            Pattern.compile("(?i)from\\s+now\\s+on\\s*,?\\s+you\\s+(are|will|must)"),
            Pattern.compile("(?i)a\\s+partir\\s+de\\s+agora\\s*,?\\s+voc[eê]\\s+(é|deve|vai)"),
            Pattern.compile("(?i)finja\\s+(que\\s+)?(voc[eê]\\s+)?(n[aã]o\\s+tem|[ée])"),
            Pattern.compile("(?i)pretend\\s+(that\\s+)?you\\s+(are|have\\s+no)"),
            // developer / jailbreak modes
            Pattern.compile("(?i)modo\\s+desenvolvedor"),
            Pattern.compile("(?i)developer\\s+mode"),
            Pattern.compile("(?i)\\bDAN\\b"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)modo\\s+sem\\s+restri[cç][õo]es"),
            Pattern.compile("(?i)unrestricted\\s+mode"),
            Pattern.compile("(?i)sem\\s+filtros?\\s+(de\\s+seguran[cç]a|[ée]ticos)")
    );

    private final List<Pattern> patterns;

    public PromptInjectionGuard(List<Pattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /** Uses only {@link #DEFAULT_PATTERNS}. */
    public static PromptInjectionGuard defaultPatterns() {
        return new PromptInjectionGuard(DEFAULT_PATTERNS);
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
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return GuardResult.block("prompt-injection-suspected",
                        "Input matches a known prompt-injection heuristic (pattern: " + pattern.pattern() + ")");
            }
        }
        return GuardResult.pass();
    }
}
