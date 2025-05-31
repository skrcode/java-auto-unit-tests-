package com.github.skrcode.javaautounittests.settings;

import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget.MultipleTextValuesPresentation;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

public class AIStatusWidget implements StatusBarWidget, MultipleTextValuesPresentation {

    private final Project project;

    public AIStatusWidget(Project project) {
        this.project = project;
    }

    @NotNull
    @Override
    public String ID() {
        return "AIStatusWidget";
    }

    @Nullable
    @Override
    public WidgetPresentation getPresentation() {
        return this;
    }

    @Nullable
    @Override
    public String getTooltipText() {
        return "Click to configure OpenAI API key and model";
    }

    @Nullable
    @Override
    public Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            // Open your settings dialog
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "JAIPilot - AI Unit Test Generator");
        };
    }

    @Override
    public @Nullable String getSelectedValue() {
        String model = AISettings.getInstance().getModel();
        return "OpenAI: " + model;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {}

    @Override
    public void dispose() {}
}
