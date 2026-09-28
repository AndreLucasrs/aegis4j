package dev.aegis4j.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Guards every non-health endpoint with a static bearer token when the server is
 * configured with one (see {@code AEGIS4J_SERVER_API_KEY}). Registered as a global
 * {@code before} filter and only wired up when a key is configured, so servers that
 * don't set the env var keep today's open behavior.
 */
public final class BearerAuthHandler implements Handler {

    private static final String HEALTH_PATH = "/health";
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedApiKeyBytes;
    private final ObjectMapper mapper;

    public BearerAuthHandler(String expectedApiKey, ObjectMapper mapper) {
        this.expectedApiKeyBytes = expectedApiKey.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        if (HEALTH_PATH.equals(ctx.path())) {
            return;
        }

        String token = bearerToken(ctx.header("Authorization"));
        // MessageDigest.isEqual compares in constant time with respect to the
        // *contents* of equal-length arrays, unlike String.equals, so it doesn't
        // leak where in the key two candidates first differ. It still short-circuits
        // on a length mismatch, which in theory leaks the key's length via timing —
        // low severity and impractical to exploit here, but worth naming precisely.
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedApiKeyBytes)) {
            ctx.status(401).contentType("application/json");
            ctx.result(mapper.writeValueAsString(Map.of(
                    "error", Map.of("code", "unauthorized", "message", "Missing or invalid API key")
            )));
            ctx.skipRemainingHandlers();
        }
    }

    private static String bearerToken(String authorizationHeader) {
        // RFC 7235 auth-scheme names are case-insensitive, so "bearer"/"BEARER"/"Bearer"
        // must all be accepted.
        if (authorizationHeader == null
                || authorizationHeader.length() < BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
