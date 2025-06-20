package com.github.skrcode.javaautounittests;


import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class PromptBuilder {

    public static String loadPromptFromUrl(String url) {
        try (InputStream in = new URL(url).openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt from: " + url, e);
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
