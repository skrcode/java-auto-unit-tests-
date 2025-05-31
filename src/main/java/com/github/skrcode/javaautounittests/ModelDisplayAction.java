package com.github.skrcode.javaautounittests;

import com.github.skrcode.javaautounittests.settings.AISettings;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class ModelDisplayAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return super.getActionUpdateThread();
    }

    public ModelDisplayAction() {
        super("Model: " + AISettings.getInstance().getModel());
    }



    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(false);
        e.getPresentation().setVisible(true);
        e.getPresentation().setText("Model: " + AISettings.getInstance().getModel());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Do nothing
    }
}
