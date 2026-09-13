package dev.aegis4j.guardrails.builtin;

import java.util.regex.Pattern;

public enum PiiPattern {

    EMAIL("[EMAIL_REDACTED]", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
    PHONE("[PHONE_REDACTED]", Pattern.compile("\\+?\\d{1,3}?[\\s.-]?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}")),
    API_KEY("[API_KEY_REDACTED]", Pattern.compile("sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}")),
    IPV4("[IP_REDACTED]", Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")),
    CREDIT_CARD("[CREDIT_CARD_REDACTED]", Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"));

    private final String placeholder;
    private final Pattern pattern;

    PiiPattern(String placeholder, Pattern pattern) {
        this.placeholder = placeholder;
        this.pattern = pattern;
    }

    public String placeholder() {
        return placeholder;
    }

    public Pattern pattern() {
        return pattern;
    }
}
