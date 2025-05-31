package com.github.skrcode.javaautounittests;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;          // optional but handy
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class DoWorkInBackgroundAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();   // may be null if triggered from Welcome screen
        if (project == null) return;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Indexing large files", /* cancellable = */ true) {

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);      // we know total steps

                final int total = 100;                  // pretend we have 100 units of work
                for (int i = 0; i < total && !indicator.isCanceled(); i++) {
                    // ----- long-running unit of work -----
                    doHeavyWorkUnit(i);

                    // update UI
                    indicator.setFraction((double) (i + 1) / total);        // 0.0 – 1.0
                    indicator.setText("Processed " + (i + 1) + " of " + total);
                }
            }

            @Override
            public void onCancel() {
                // clean-up if user clicks the red ⨉
            }

            @Override
            public void onSuccess() {
                // runs on EDT after successful completion (not canceled, no exception)
            }

            private void doHeavyWorkUnit(int i) {
                try {
                    Thread.sleep(50); // simulate expensive work
                } catch (InterruptedException ignored) { }
            }
        });
    }
}
