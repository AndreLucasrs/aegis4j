package dev.aegis4j.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record OllamaTagsResponse(List<OllamaModel> models) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OllamaModel(String name) {
    }
}
