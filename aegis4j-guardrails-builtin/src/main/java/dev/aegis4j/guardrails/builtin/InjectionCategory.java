package dev.aegis4j.guardrails.builtin;

import java.util.regex.Pattern;

/**
 * Named, individually addressable prompt-injection heuristics used by
 * {@link PromptInjectionGuard}'s default pattern set — mirrors how
 * {@link PiiPattern} groups {@link RegexPiiGuard}'s patterns, so a specific
 * category can be selected, disabled, or audited on its own instead of
 * treating the defaults as one opaque block.
 */
public enum InjectionCategory {

    IGNORE_INSTRUCTIONS_PT(Pattern.compile(
            "(?i)ignor[ae]\\s+(todas\\s+)?(as\\s+|suas\\s+)?instru[cç][õo]es\\s+(anterior(es)?|acima|pr[ée]via)")),
    IGNORE_INSTRUCTIONS_EN(Pattern.compile(
            "(?i)ignore\\s+(all\\s+|your\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions")),
    DISREGARD_INSTRUCTIONS_PT(Pattern.compile(
            "(?i)desconsidere\\s+(as\\s+)?instru[cç][õo]es\\s+(anterior(es)?|acima)")),
    DISREGARD_INSTRUCTIONS_EN(Pattern.compile(
            "(?i)disregard\\s+(all\\s+|your\\s+)?(previous|prior|above)\\s+(instructions|rules|prompt)")),
    FORGET_RULES_PT(Pattern.compile("(?i)esque[cç]a\\s+(todas\\s+)?(as\\s+)?(suas\\s+)?regras")),
    FORGET_RULES_EN(Pattern.compile("(?i)forget\\s+(all\\s+|your\\s+)?(previous\\s+)?(rules|instructions|guidelines)")),

    REVEAL_SYSTEM_PROMPT_PT(Pattern.compile("(?i)revele?\\s+(o\\s+|seu\\s+)?system\\s*prompt")),
    REVEAL_SYSTEM_PROMPT_EN(Pattern.compile("(?i)reveal\\s+(your|the)\\s+system\\s*prompt")),
    SHOW_SYSTEM_INSTRUCTIONS_PT(Pattern.compile(
            "(?i)(mostre|exiba|imprima|repita)\\s+(suas\\s+|as\\s+)?instru[cç][õo]es\\s+(de\\s+sistema|iniciais)")),
    SHOW_SYSTEM_INSTRUCTIONS_EN(Pattern.compile("(?i)(show|print|repeat)\\s+(me\\s+)?your\\s+(system\\s+)?(prompt|instructions)")),
    ASK_ORIGINAL_INSTRUCTIONS_EN(Pattern.compile("(?i)what\\s+(are|were)\\s+your\\s+(original\\s+)?instructions")),
    ASK_ORIGINAL_INSTRUCTIONS_PT(Pattern.compile(
            "(?i)quais\\s+s[aã]o\\s+suas\\s+instru[cç][õo]es\\s+(originais|iniciais|de\\s+sistema)")),

    ROLE_OVERRIDE_PT(Pattern.compile("(?i)voc[eê]\\s+agora\\s+[ée]\\s+")),
    ROLE_OVERRIDE_EN(Pattern.compile("(?i)you\\s+are\\s+now\\s+")),
    FROM_NOW_ON_EN(Pattern.compile("(?i)from\\s+now\\s+on\\s*,?\\s+you\\s+(are|will|must)")),
    FROM_NOW_ON_PT(Pattern.compile("(?i)a\\s+partir\\s+de\\s+agora\\s*,?\\s+voc[eê]\\s+(é|deve|vai)")),
    PRETEND_NO_RULES_PT(Pattern.compile("(?i)finja\\s+(que\\s+)?(voc[eê]\\s+)?(n[aã]o\\s+tem|[ée])")),
    PRETEND_NO_RULES_EN(Pattern.compile("(?i)pretend\\s+(that\\s+)?you\\s+(are|have\\s+no)")),

    DEVELOPER_MODE_PT(Pattern.compile("(?i)modo\\s+desenvolvedor")),
    DEVELOPER_MODE_EN(Pattern.compile("(?i)developer\\s+mode")),
    DAN_JAILBREAK(Pattern.compile("(?i)\\bDAN\\b")),
    JAILBREAK_EN(Pattern.compile("(?i)jailbreak")),
    UNRESTRICTED_MODE_PT(Pattern.compile("(?i)modo\\s+sem\\s+restri[cç][õo]es")),
    UNRESTRICTED_MODE_EN(Pattern.compile("(?i)unrestricted\\s+mode")),
    NO_SAFETY_FILTERS_PT(Pattern.compile("(?i)sem\\s+filtros?\\s+(de\\s+seguran[cç]a|[ée]ticos)"));

    private final Pattern pattern;

    InjectionCategory(Pattern pattern) {
        this.pattern = pattern;
    }

    public Pattern pattern() {
        return pattern;
    }
}
