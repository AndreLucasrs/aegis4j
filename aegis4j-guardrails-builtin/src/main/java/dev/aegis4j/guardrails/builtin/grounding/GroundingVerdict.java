package dev.aegis4j.guardrails.builtin.grounding;

/**
 * The judge {@link dev.aegis4j.api.provider.Provider}'s structured answer to
 * "is this response grounded in the retrieved context?". {@code confidence}
 * is the judge's own self-reported 0.0-1.0 estimate (not calibrated against
 * anything) and {@code reason} is a short human-readable explanation used in
 * the warning/block message.
 */
record GroundingVerdict(boolean grounded, double confidence, String reason) {
}
