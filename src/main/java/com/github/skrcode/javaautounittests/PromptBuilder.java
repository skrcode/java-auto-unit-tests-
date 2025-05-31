package com.github.skrcode.javaautounittests;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class PromptBuilder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String build(ContextModel ctx) {
        try {
            String yaml = MAPPER.writeValueAsString(ctx);
            StringBuilder sb = new StringBuilder();
            sb.append("You are an elite Java test generator.\n")
                    .append("If existingTestSource is present, update it; otherwise create anew.\n")
                    .append("Aim for >90% line coverage.\n")
                    .append("Use JUnit 5, Mockito, AssertJ.\n")
                    .append("---\n")
                    .append(yaml)
                    .append("---");
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}