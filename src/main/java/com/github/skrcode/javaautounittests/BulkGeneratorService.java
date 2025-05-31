package com.github.skrcode.javaautounittests;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Spins up a background task that iterates over many classes sequentially.
 */
public final class BulkGeneratorService {

    public static void enqueue(Project project, List<PsiClass> classes) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "JAIPilot – Generating tests for " + classes.size() + " class(es)", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                int idx = 0;
                for (PsiClass cut : classes) {
                    if (indicator.isCanceled()) break;
                    indicator.setText("Processing " + cut.getQualifiedName());
                    indicator.setFraction(idx++ / (double) classes.size());
                    TestGenerationWorker.process(project, cut, indicator);
                }
            }
        });
    }
}
