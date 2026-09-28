package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiFunctionCall(String name, String arguments) {
}
