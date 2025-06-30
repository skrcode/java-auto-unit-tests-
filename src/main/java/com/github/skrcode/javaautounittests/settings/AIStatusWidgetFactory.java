package com.github.skrcode.javaautounittests.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.openapi.wm.impl.status.TextPanel;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.event.MouseEvent;

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
        return false; // change to `true` if you want to show something later
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new StatusBarWidget() {
            @Override
            public @NotNull String ID() {
                return "noop-statusbar-widget";
            }

            @Override
            public @Nullable WidgetPresentation getPresentation() {
                return new StatusBarWidget.TextPresentation() {
                    @Override
                    public @NotNull String getText() {
                        return ""; // invisible text
                    }

                    @Override
                    public float getAlignment() {
                        return Component.CENTER_ALIGNMENT;
                    }

                    @Override
                    public @Nullable String getTooltipText() {
                        return null;
                    }

                    @Override
                    public @Nullable Consumer<MouseEvent> getClickConsumer() {
                        return null;
                    }
                };
            }

            @Override
            public void install(@NotNull StatusBar statusBar) {}

            @Override
            public void dispose() {}
        };
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
