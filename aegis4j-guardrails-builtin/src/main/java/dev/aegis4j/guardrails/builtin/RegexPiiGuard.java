package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Deterministic regex-based PII/secret detector. Runs on both input and
 * output by default and redacts matches in place (a {@code Modify} result)
 * rather than blocking, so a conversation can continue with sensitive data
 * scrubbed out.
 */
public final class RegexPiiGuard extends GuardAdapter {

    private final Set<PiiPattern> enabled;

    public RegexPiiGuard(Set<PiiPattern> enabled) {
        this.enabled = EnumSet.copyOf(enabled);
    }

    public static RegexPiiGuard allPatterns() {
        return new RegexPiiGuard(EnumSet.allOf(PiiPattern.class));
    }

    public static RegexPiiGuard of(PiiPattern... patterns) {
        return new RegexPiiGuard(Set.of(patterns));
    }

    @Override
    public String id() {
        return "regex-pii";
    }

    @Override
    public GuardResult checkInput(GuardContext ctx, String text) {
        return redact(text);
    }

    @Override
    public GuardResult checkOutput(GuardContext ctx, String text) {
        return redact(text);
    }

    private GuardResult redact(String text) {
        String result = text;
        boolean matchedAny = false;
        for (PiiPattern pattern : enabled) {
            String replaced = redactPattern(pattern, result);
            if (!replaced.equals(result)) {
                matchedAny = true;
            }
            result = replaced;
        }
        return matchedAny ? GuardResult.modify(result, "pii-redacted") : GuardResult.pass();
    }

    private String redactPattern(PiiPattern pattern, String text) {
        if (pattern != PiiPattern.CREDIT_CARD) {
            return pattern.pattern().matcher(text).replaceAll(pattern.placeholder());
        }
        Matcher matcher = pattern.pattern().matcher(text);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            String candidate = matcher.group().replaceAll("[ -]", "");
            if (candidate.length() >= 13 && candidate.length() <= 19 && passesLuhn(candidate)) {
                sb.append(text, lastEnd, matcher.start()).append(pattern.placeholder());
                lastEnd = matcher.end();
            }
        }
        sb.append(text.substring(lastEnd));
        return sb.toString();
    }

    private boolean passesLuhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
