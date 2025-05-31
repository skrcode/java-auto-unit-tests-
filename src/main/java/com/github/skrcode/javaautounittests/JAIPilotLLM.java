package com.github.skrcode.javaautounittests;

import com.github.skrcode.javaautounittests.settings.AISettings;
import com.intellij.openapi.ui.Messages;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;

import java.util.stream.Collectors;

/** Convenience façade so we can switch out or mock in tests. */
public final class JAIPilotLLM {
    public static String invokeAI(String prompt) {
        try {
            OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();

            StructuredResponseCreateParams<ResponseOutput> params = ResponseCreateParams.builder()
                    .input(prompt)
                    .text(ResponseOutput.class)
                    .model(ChatModel.GPT_4_1_NANO)
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
}