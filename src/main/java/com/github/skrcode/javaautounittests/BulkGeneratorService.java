package com.github.skrcode.javaautounittests;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Spins up a background task that iterates over many classes sequentially.
 */
public final class BulkGeneratorService {

    public static void enqueue(Project project, List<PsiClass> classes) {
        ProgressManager.getInstance().run(new Task.Backgroundable(
                project,
                "JAIPilot – Generating tests for " + classes.size() + " class(es)",
                /* cancellable = */ true      // ✅ enables red "×" cancel button
        ) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                int idx = 0;
                for (PsiClass cut : classes) {
                    if (indicator.isCanceled()) break;

                    indicator.setText("Processing " + cut.getQualifiedName());
                    indicator.setFraction(idx++ / (double) classes.size());

                    // Resolve test root inside a write action (once per class)
                    PsiDirectory testRoot = WriteCommandAction.writeCommandAction(project).compute(() ->
                            TestRootLocator.getOrCreateTestRoot(project, cut.getContainingFile())
                    );

                    TestGenerationWorker.process(project, cut, indicator, testRoot);
                }
            }

            @Override
            public void onCancel() {
                // Optional: log, cleanup, or notify user
                System.out.println("JAIPilot bulk generation cancelled by user.");
            }

            @Override
            public void onSuccess() {
//                ApplicationManager.getApplication().invokeLater(() -> {
//                    NotificationGroupManager.getInstance()
//                            .getNotificationGroup("JAIPilot - AI Unit Test Generator Feedback")
//                            .createNotification(
//                                    "All tests generated!",
//                                    "If JAIPilot helped you, please leave a review and ⭐️ rate it - it helps a lot! ",
//                                    NotificationType.INFORMATION
//                            )
//                            .setListener((notification, event) -> {
//                                String url = event.getURL().toString();
//                                if (url.startsWith("https://plugins.jetbrains.com")) {
//                                    BrowserUtil.browse(url);
//                                    notification.expire();
//                                }
//                            })
//                            .notify(project);
//                });
            }

        });
    }
}
