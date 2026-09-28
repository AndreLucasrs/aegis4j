package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaOutputGuardTest {

    private final GuardContext ctx = new GuardContext("req-1", "user-1", Map.of());

    private static final Map<String, Object> PERSON_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "age", Map.of("type", "integer", "minimum", 0)
            ),
            "required", List.of("name", "age"),
            "additionalProperties", false
    );

    @Test
    void passesWhenOutputMatchesSchema() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        GuardResult result = guard.checkOutput(ctx, "{\"name\": \"Andre\", \"age\": 34}");
        assertThat(result).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void blocksWhenOutputIsNotJson() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        GuardResult result = guard.checkOutput(ctx, "this is not json at all");
        assertThat(result).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("output-not-json");
    }

    @Test
    void blocksWhenRequiredFieldIsMissing() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        GuardResult result = guard.checkOutput(ctx, "{\"name\": \"Andre\"}");
        assertThat(result).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("output-schema-mismatch");
    }

    @Test
    void blocksWhenFieldHasWrongType() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        GuardResult result = guard.checkOutput(ctx, "{\"name\": \"Andre\", \"age\": \"thirty-four\"}");
        assertThat(result).isInstanceOf(GuardResult.Block.class);
    }

    @Test
    void blocksWhenAdditionalPropertiesArePresent() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        GuardResult result = guard.checkOutput(ctx, "{\"name\": \"Andre\", \"age\": 34, \"ssn\": \"123-45-6789\"}");
        assertThat(result).isInstanceOf(GuardResult.Block.class);
    }

    @Test
    void blocksWhenNumericConstraintIsViolated() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        GuardResult result = guard.checkOutput(ctx, "{\"name\": \"Andre\", \"age\": -1}");
        assertThat(result).isInstanceOf(GuardResult.Block.class);
    }

    @Test
    void passesForArraySchema() {
        Map<String, Object> arraySchema = Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        );
        var guard = JsonSchemaOutputGuard.of(arraySchema);
        assertThat(guard.checkOutput(ctx, "[\"a\", \"b\", \"c\"]")).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void blocksEmptyOutput() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        GuardResult result = guard.checkOutput(ctx, "");
        assertThat(result).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("output-not-json");
    }

    @Test
    void inputIsAlwaysPassThrough() {
        var guard = JsonSchemaOutputGuard.of(PERSON_SCHEMA);
        assertThat(guard.checkInput(ctx, "not json")).isInstanceOf(GuardResult.Pass.class);
    }
}
