package dev.aegis4j.api.provider;

import java.util.Map;

/**
 * How the provider should shape its output. {@code JsonSchema} is wired through
 * the contract in v0.1 for API stability but is not yet enforced end-to-end
 * (that lands with {@code JsonSchemaOutputGuard} in v0.2).
 */
public sealed interface ResponseFormat {

    record Text() implements ResponseFormat {
    }

    record JsonSchema(String name, Map<String, Object> schema, boolean strict) implements ResponseFormat {
    }

    Text TEXT = new Text();
}
