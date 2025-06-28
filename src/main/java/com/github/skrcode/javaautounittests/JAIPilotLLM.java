package com.github.skrcode.javaautounittests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skrcode.javaautounittests.DTOs.ResponseOutput;
import com.github.skrcode.javaautounittests.DTOs.ScenariosResponseOutput;
import com.github.skrcode.javaautounittests.settings.AISettings;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.google.genai.Client;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Convenience façade so we can switch out or mock in tests. */
public final class JAIPilotLLM {
    public static String invokeAI(String prompt) {
        try {
            OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();

            StructuredResponseCreateParams<ResponseOutput> params = ResponseCreateParams.builder()
                    .input(prompt)
                    .text(ResponseOutput.class)
                    .model(AISettings.getInstance().getModel())
                    .build();

            return client.responses().create(params).output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .map(responseTestClass -> responseTestClass.outputTestClass).collect(Collectors.joining());
        } catch (Throwable t) {
            t.printStackTrace();
            Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error");
            return "ERROR: " + t.getMessage();
        }
    }

    public static ScenariosResponseOutput getScenarios(String promptPlaceholder, String inputClass) {  // TODO: needs current testclass as well to exclude scenarios.
        try {
            String prompt = promptPlaceholder.replace("{{inputclass}}", inputClass);
            Schema methodSchema = Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(ImmutableMap.of(
                            "methodname", Schema.builder().type(Type.Known.STRING).description("Name of actual method that is to be tested").build(),
                            "returntype", Schema.builder().type(Type.Known.STRING).description("Return Type of actual method that is to be tested").build(),
                            "scenario", Schema.builder().type(Type.Known.STRING).description("Short description of the test scenario").build()
                    )).build();
            Schema schema = Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(ImmutableMap.of(
                            "testScenarios", Schema.builder()
                                    .type(Type.Known.ARRAY)
                                    .items(methodSchema)
                                    .description("List of test scenarios")
                                    .build()
                    )).build();
            GenerateContentResponse parsed = invokeGemini(prompt, schema);
            ObjectMapper mapper = new ObjectMapper();
            ScenariosResponseOutput response = mapper.readValue(parsed.text(), ScenariosResponseOutput.class);
            return response;
        }
        catch (Throwable t) {
            t.printStackTrace();
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error")
            );
            return null;
        }
    }

    public static String getSingleTest(String promptPlaceholder, String inputClass, ScenariosResponseOutput.TestScenario testScenario, String existingTestClass, String errorOutput) {
        try {
            Map<String, String> placeholders = Map.of(
                    "{{inputclass}}", inputClass,
                    "{{testclass}}", existingTestClass,
                    "{{erroroutput}}", errorOutput,
                    "{{testscenario}}", testScenario.toString()
            );

            String prompt = promptPlaceholder;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                prompt = prompt.replace(entry.getKey(), entry.getValue());
            }

            Schema schema = Schema.builder().type(Type.Known.OBJECT).properties(ImmutableMap.of("outputTestClass", Schema.builder().type(Type.Known.STRING).description("Output Test Class").build())).build();
            GenerateContentResponse parsed = invokeGemini(prompt, schema);
            ObjectMapper mapper = new ObjectMapper();
            ResponseOutput response = mapper.readValue(parsed.text(), ResponseOutput.class);
            return response.outputTestClass;
        }
        catch (Throwable t) {
            t.printStackTrace();
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error")
            );
            return "ERROR: " + t.getMessage();
        }
    }

    public static String getAggregatedTests(String promptPlaceholder, String existingTestClass, List<String> additionalTestClasses, String errorOutput) {
        try {
            Map<String, String> placeholders = Map.of(
                    "{{testclass}}", existingTestClass,
                    "{{erroroutput}}", errorOutput,
                    "{{additionaltestclasses}}", additionalTestClasses.toString()
            );

            String prompt = promptPlaceholder;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                prompt = prompt.replace(entry.getKey(), entry.getValue());
            }

            Schema schema = Schema.builder().type(Type.Known.OBJECT).properties(ImmutableMap.of("outputTestClass", Schema.builder().type(Type.Known.STRING).description("Output Test Class").build())).build();
            GenerateContentResponse parsed = invokeGemini(prompt, schema);
            ObjectMapper mapper = new ObjectMapper();
            ResponseOutput response = mapper.readValue(parsed.text(), ResponseOutput.class);
            return response.outputTestClass;
        }
        catch (Throwable t) {
            t.printStackTrace();
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error")
            );
            return "ERROR: " + t.getMessage();
        }
    }

    private static GenerateContentResponse invokeGemini(String prompt, Schema schema) throws JsonProcessingException {
        Client client = Client.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();
        GenerateContentConfig generateContentConfig = GenerateContentConfig.builder().responseMimeType("application/json").candidateCount(1).responseSchema(schema).build();
        GenerateContentResponse response = client.models.generateContent("gemini-2.5-flash-lite-preview-06-17", prompt, generateContentConfig);
        return response;
    }
}