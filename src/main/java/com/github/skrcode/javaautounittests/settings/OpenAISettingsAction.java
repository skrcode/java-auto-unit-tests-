package com.github.skrcode.javaautounittests.settings;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class OpenAISettingsAction extends AnAction {
    public OpenAISettingsAction() {
        super("Settings");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "JAIPilot - AI Unit Test Generator");
    }
}
