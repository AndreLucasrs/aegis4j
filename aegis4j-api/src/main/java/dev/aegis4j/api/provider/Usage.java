package dev.aegis4j.api.provider;

public record Usage(int promptTokens, int completionTokens, int totalTokens) {

    public static final Usage UNKNOWN = new Usage(0, 0, 0);
}
