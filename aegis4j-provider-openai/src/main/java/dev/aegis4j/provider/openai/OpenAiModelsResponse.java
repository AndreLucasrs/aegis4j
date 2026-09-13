package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiModelsResponse(List<OpenAiModel> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenAiModel(String id) {
    }
}
