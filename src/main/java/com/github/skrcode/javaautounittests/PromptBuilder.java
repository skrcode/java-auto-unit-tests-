package com.github.skrcode.javaautounittests;


import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PromptBuilder {

    public static String getPromptPlaceholder(String fileName) {
        String promptUrl = "https://raw.githubusercontent.com/skrcode/java-auto-unit-tests/refs/heads/feature/scenarios-bulk-context/src/main/resources/"+fileName;
        try (InputStream in = new URL(promptUrl).openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt from: " + promptUrl, e);
        }
    }

    public static Map<String, List<String>> getModels() {
        String promptUrl = "https://raw.githubusercontent.com/skrcode/java-auto-unit-tests/refs/heads/feature/scenarios-bulk-context/src/main/resources/models.json";

        try (InputStream in = new URL(promptUrl).openStream()) {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<String>> modelMap = mapper.readValue(in, Map.class);

            return modelMap;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load models from: " + promptUrl, e);
        }
    }
}
