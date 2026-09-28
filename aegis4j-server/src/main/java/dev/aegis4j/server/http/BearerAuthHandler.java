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
        // MessageDigest.isEqual runs in constant time regardless of where the
        // arrays first differ, unlike String.equals, so it doesn't leak the
        // key's length/prefix through response-time differences.
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedApiKeyBytes)) {
            ctx.status(401).contentType("application/json");
            ctx.result(mapper.writeValueAsString(Map.of(
                    "error", Map.of("code", "unauthorized", "message", "Missing or invalid API key")
            )));
            ctx.skipRemainingHandlers();
        }
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
