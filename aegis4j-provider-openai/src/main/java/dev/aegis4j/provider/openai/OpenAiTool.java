package dev.aegis4j.provider.openai;

record OpenAiTool(String type, OpenAiFunctionDef function) {

    static OpenAiTool function(OpenAiFunctionDef function) {
        return new OpenAiTool("function", function);
    }
}
