package com.github.skrcode.javaautounittests;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.openai.models.ChatModel;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.google.genai.Client;

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

    public static String invokeAIGemini(String prompt) {
        try {

            Client client = Client.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();
            Schema schema = Schema.builder().type(Type.Known.OBJECT).properties(ImmutableMap.of("outputTestClass", Schema.builder().type(Type.Known.STRING).description("Output Test Class").build())).build();
            GenerateContentConfig generateContentConfig = GenerateContentConfig.builder().responseMimeType("application/json").candidateCount(1).responseSchema(schema).build();
            GenerateContentResponse response = client.models.generateContent("gemini-2.5-flash-lite-preview-06-17", prompt, generateContentConfig);
            ObjectMapper mapper = new ObjectMapper();
            ResponseOutput parsed = mapper.readValue(response.text(), ResponseOutput.class);

            return parsed.outputTestClass;

        } catch (Throwable t) {
            t.printStackTrace();
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error")
            );

//            Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error");
            return "ERROR: " + t.getMessage();
        }
    }
}