package com.github.skrcode.javaautounittests.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class AISettingsDialog extends DialogWrapper {

    private final AISettingsConfigurable configurable;
    private JComponent centerPanel;

    public AISettingsDialog() {
        super(true); // use current window as parent
        setTitle("JAIPilot Settings");

        this.configurable = new AISettingsConfigurable();

        // Important: createComponent must be called before reset/apply
        this.centerPanel = configurable.createComponent();
        configurable.reset(); // load current values into UI

        init(); // initializes dialog wrapper
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return centerPanel;
    }

    @Override
    protected void doOKAction() {
        try {
            configurable.apply(); // persist updated values
        } catch (Exception e) {
            // Log or show dialog error if needed
            e.printStackTrace();
        }
        super.doOKAction();
    }

    @Override
    public void doCancelAction() {
        configurable.reset(); // discard changes
        super.doCancelAction();
    }
}
