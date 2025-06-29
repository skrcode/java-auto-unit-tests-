package com.github.skrcode.javaautounittests;


import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class PromptBuilder {

    public static String getPromptPlaceholder(String fileName) {
        String promptUrl = "https://raw.githubusercontent.com/skrcode/java-auto-unit-tests/refs/heads/feature/scenarios/src/main/resources/"+fileName;
        try (InputStream in = new URL(promptUrl).openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt from: " + promptUrl, e);
        }
    }

    public static String build(String basePrompt, ContextModel ctx) {
        if (basePrompt == null || ctx == null) {
            throw new IllegalArgumentException("basePrompt and ctx must not be null");
        }

        return basePrompt
//                .replace("{{inputclass}}", safe(ctx.qualifiedName))
                .replace("{{inputclass}}", safe(ctx.fullSource))
                .replace("{{erroroutput}}", safe(ctx.errorMessage))
//                .replace("{{outputCoverage}}", safe(ctx.outputCoverage))
                .replace("{{testclass}}", safe(ctx.existingTestSource));
    }

    private static String safe(String val) {
        return val != null ? val : "";
    }
}
