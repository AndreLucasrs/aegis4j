package dev.aegis4j.guardrails.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that model output is well-formed JSON matching a caller-supplied
 * <a href="https://json-schema.org/">JSON Schema</a>, expressed the same way
 * as {@link dev.aegis4j.api.provider.ToolDefinition#parametersSchema()} — a
 * plain {@code Map<String, Object>} — for consistency with the rest of the
 * API. Useful for enforcing structured-output contracts (e.g. a tool-call
 * result or a "respond only in this JSON shape" instruction) rather than
 * trusting the model to have followed the shape it was asked for.
 *
 * <p>Output that is not valid JSON, or that is valid JSON but does not
 * satisfy the schema, is rejected via {@link GuardResult#block}; the guard
 * never attempts to repair or coerce the output.
 */
public final class JsonSchemaOutputGuard extends GuardAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchema schema;

    public JsonSchemaOutputGuard(Map<String, Object> schema) {
        this(schema, SpecVersion.VersionFlag.V202012);
    }

    /**
     * @param specVersion draft used when {@code schema} does not declare its
     *                     own {@code $schema} keyword.
     */
    public JsonSchemaOutputGuard(Map<String, Object> schema, SpecVersion.VersionFlag specVersion) {
        JsonNode schemaNode = MAPPER.valueToTree(schema);
        this.schema = JsonSchemaFactory.getInstance(specVersion).getSchema(schemaNode);
    }

    public static JsonSchemaOutputGuard of(Map<String, Object> schema) {
        return new JsonSchemaOutputGuard(schema);
    }

    @Override
    public String id() {
        return "json-schema-output";
    }

    @Override
    public GuardResult checkOutput(GuardContext ctx, String text) {
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return GuardResult.block("output-not-json", "Output is not valid JSON: " + e.getOriginalMessage());
        }
        // readTree(String) returns null/MissingNode for blank input instead of throwing.
        if (node == null || node.isMissingNode()) {
            return GuardResult.block("output-not-json", "Output is not valid JSON: empty content");
        }

        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            String detail = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            return GuardResult.block("output-schema-mismatch", "Output does not match the expected JSON schema: " + detail);
        }
        return GuardResult.pass();
    }
}
