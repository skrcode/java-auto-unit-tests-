package com.github.skrcode.javaautounittests.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

public class AIStatusWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return "AIStatusWidget";
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "OpenAI Model Status";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project, @NotNull CoroutineScope scope) {
        return StatusBarWidgetFactory.super.createWidget(project, scope);
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return StatusBarWidgetFactory.super.createWidget(project);
    }

    public void createWidget(@NotNull Project project, @NotNull StatusBar statusBar) {
        statusBar.addWidget(new AIStatusWidget(project), "after Encoding", project);
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}

