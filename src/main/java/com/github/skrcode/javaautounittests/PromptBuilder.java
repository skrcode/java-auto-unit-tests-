package com.github.skrcode.javaautounittests;


import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class PromptBuilder {

    public static String getPromptPlaceholder(String fileName) {
        String promptUrl = "https://raw.githubusercontent.com/skrcode/java-auto-unit-tests/refs/heads/feature/scenarios-bulk-context/src/main/resources/"+fileName;
        try (InputStream in = new URL(promptUrl).openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt from: " + promptUrl, e);
        }
    }
}
