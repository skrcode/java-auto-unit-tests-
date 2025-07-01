package com.github.skrcode.javaautounittests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skrcode.javaautounittests.DTOs.ContextClassesResponseOutput;
import com.github.skrcode.javaautounittests.DTOs.ResponseOutput;
import com.github.skrcode.javaautounittests.DTOs.ScenariosResponseOutput;
import com.github.skrcode.javaautounittests.DTOs.SingleTestPromptResponseOutput;
import com.github.skrcode.javaautounittests.settings.AISettings;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.google.genai.Client;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    public static List<List<String>> getContextClassesForAllTests(String promptPlaceholder, String inputClass, List<ScenariosResponseOutput.TestScenario> testScenarios, List<String> existingTestClasses) {
        ExecutorService executor = Executors.newCachedThreadPool();
        List<CompletableFuture<List<String>>> futures = new ArrayList<>();
        Schema schema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(ImmutableMap.of(
                        "outputRequiredClassContextPaths", Schema.builder()
                                .type(Type.Known.ARRAY)
                                .items(Schema.builder().type(Type.Known.STRING).build()) // ✅ REQUIRED
                                .description("Output Required Classes Paths for Additional Context")
                                .build()
                ))
                .build();


        Client client = Client.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();
        GenerateContentConfig generateContentConfig = GenerateContentConfig.builder().responseMimeType("application/json").candidateCount(1).responseSchema(schema).build();
        ObjectMapper mapper = new ObjectMapper();

        for (int i = 0; i < testScenarios.size(); i++) {
            int idx = i; // for lambda capture

            Map<String, String> placeholders = Map.of(
                    "{{inputclass}}", inputClass,
                    "{{testclass}}", existingTestClasses.get(idx),
                    "{{testscenario}}", testScenarios.get(idx).toString()
            );

            String prompt = promptPlaceholder;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                prompt = prompt.replace(entry.getKey(), entry.getValue());
            }
            String finalPrompt = prompt;


            // parallelize only the network-bound call
            CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    GenerateContentResponse response = invokeGeminiApi(finalPrompt, schema, client, generateContentConfig);
                    ContextClassesResponseOutput parsed = mapper.readValue(response.text(), ContextClassesResponseOutput.class);
                    return parsed.outputRequiredClassContextPaths;
                } catch (Throwable t) {
                    t.printStackTrace();
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error")
                    );
                    return new ArrayList<>();
                }
            }, executor);

            futures.add(future);
        }

        List<List<String>> testClasses = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        executor.shutdown();

        return testClasses;
    }

    public static SingleTestPromptResponseOutput getAllSingleTest(Set<Integer> completedTests, String promptPlaceholder, List<String> testClassNames, String inputClass, List<ScenariosResponseOutput.TestScenario> testScenarios, List<String> existingTestClasses, List<String> errorOutputs, List<List<String>> contextClassesSourceForEachIndividualClass) {
        ExecutorService executor = Executors.newCachedThreadPool();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<String> testClasses = new ArrayList<>(Collections.nCopies(testScenarios.size(), ""));
        List<List<String>> contextClasses = new ArrayList<>(Collections.nCopies(testScenarios.size(), List.of()));


        Schema schema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(ImmutableMap.of(
                        "outputTestClass", Schema.builder()
                                .type(Type.Known.STRING)
                                .description("Output Test Class")
                                .build(),
                        "outputRequiredClassContextPaths", Schema.builder()
                                .type(Type.Known.ARRAY)
                                .items(Schema.builder().type(Type.Known.STRING).build())
                                .description("Output Required Classes Paths for Additional Context")
                                .build()
                ))
                .build();

        Client client = Client.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();
        GenerateContentConfig generateContentConfig = GenerateContentConfig.builder().responseMimeType("application/json").candidateCount(1).responseSchema(schema).build();
        ObjectMapper mapper = new ObjectMapper();

        for (int i = 0; i < testScenarios.size(); i++) {
            int idx = i;

            if (completedTests.contains(idx)) {
                testClasses.set(idx, "");
                contextClasses.set(idx, new ArrayList<>());
                continue;
            }

            Map<String, String> placeholders = Map.of(
                    "{{inputclass}}", inputClass,
                    "{{testclass}}", existingTestClasses.get(idx),
                    "{{erroroutput}}", errorOutputs.get(idx),
                    "{{testscenario}}", testScenarios.get(idx).toString(),
                    "{{testclassname}}", testClassNames.get(idx),
                    "{{contextclasses}}", contextClassesSourceForEachIndividualClass.get(idx).toString()
            );

            String prompt = promptPlaceholder;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                prompt = prompt.replace(entry.getKey(), entry.getValue());
            }
            String finalPrompt = prompt;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    GenerateContentResponse response = invokeGeminiApi(finalPrompt, schema, client, generateContentConfig);
                    ResponseOutput parsed = mapper.readValue(response.text(), ResponseOutput.class);

                    testClasses.set(idx, parsed.outputTestClass);
                    contextClasses.set(idx, parsed.outputRequiredClassContextPaths);

                } catch (Throwable t) {
                    t.printStackTrace();
                    testClasses.set(idx, "ERROR: " + t.getMessage());
                    contextClasses.set(idx, new ArrayList<>());
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error")
                    );
                }
            }, executor);

            futures.add(future);
        }

        futures.forEach(CompletableFuture::join);
        executor.shutdown();

        SingleTestPromptResponseOutput output = new SingleTestPromptResponseOutput();
        output.setTestClassCodeForEachIndividualClass(testClasses);
        output.setContextClassesForEachIndividualClass(contextClasses);
        return output;
    }

    public static String getSingleTest(String promptPlaceholder, String testClassName, String inputClass, ScenariosResponseOutput.TestScenario testScenario, String existingTestClass, String errorOutput) {
        try {
            Map<String, String> placeholders = Map.of(
                    "{{inputclass}}", inputClass,
                    "{{testclass}}", existingTestClass,
                    "{{erroroutput}}", errorOutput,
                    "{{testscenario}}", testScenario.toString(),
                    "{{testclassname}}", testClassName
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

    public static String getAggregatedTests(String promptPlaceholder, String existingTestClass, String testClassName, List<String> additionalTestClasses, String errorOutput) {
        try {
            Map<String, String> placeholders = Map.of(
                    "{{testclass}}", existingTestClass,
                    "{{erroroutput}}", errorOutput,
                    "{{additionaltestclasses}}", additionalTestClasses.toString(),
                    "{{testclassname}}", testClassName
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

    private static GenerateContentResponse invokeGemini(String prompt, Schema schema) {
        Client client = Client.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();
        GenerateContentConfig generateContentConfig = GenerateContentConfig.builder().responseMimeType("application/json").candidateCount(1).responseSchema(schema).build();
        GenerateContentResponse response = client.models.generateContent("gemini-2.5-flash-lite-preview-06-17", prompt, generateContentConfig);
        return response;
    }

    private static GenerateContentResponse invokeGeminiApi(String prompt, Schema schema, Client client, GenerateContentConfig generateContentConfig) {
        GenerateContentResponse response = client.models.generateContent("gemini-2.5-flash-lite-preview-06-17", prompt, generateContentConfig);
        return response;
    }
}