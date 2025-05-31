package com.github.skrcode.javaautounittests.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "AISettings", storages = @Storage("AISettings.xml"))
public class AISettings implements PersistentStateComponent<AISettings.State> {

    public static class State {
        public String openAiKey = "";
        public String model = "gpt-3.5-turbo";
        public String testDirectory = "";

    }

    private State state = new State();

    public static AISettings getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication().getService(AISettings.class);
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public String getOpenAiKey() {
        return state.openAiKey;
    }

    public void setOpenAiKey(String key) {
        state.openAiKey = key;
    }

    public String getModel() {
        return state.model;
    }

    public void setModel(String model) {
        state.model = model;
    }

    public void setTestDirectory(String dir) {
        state.testDirectory = dir;
    }

    public String getTestDirectory() {
        return state.testDirectory;
    }

}
