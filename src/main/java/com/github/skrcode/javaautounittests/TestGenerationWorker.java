package com.github.skrcode.javaautounittests;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.NotNull;

/**
 * Generates/updates a JUnit test for one class, runs JaCoCo, loops ≤ 5 × until
 * line‑coverage ≥ 90 %.  All PSI writes are executed inside the same EDT write‑action to
 * satisfy IntelliJ threading rules.
 */
public final class TestGenerationWorker {

    private static final int MAX_ITERATIONS  = 5;
    private static final double TARGET_RATIO = 0.90;   // 90 %

    public static void process(Project project, PsiClass cut, @NotNull ProgressIndicator ind) {
        String testFileName = cut.getName() + "Test.java";

        /* —— 0. Resolve test root *once* so the user is not prompted every iteration —— */
        PsiDirectory testRoot = WriteCommandAction.writeCommandAction(project).compute(() ->
                TestRootLocator.getOrCreateTestRoot(project, cut.getContainingFile())
        );

        Ref<PsiFile> testFile = Ref.create(testRoot.findFile(testFileName));

        for (int attempt = 1; attempt <= MAX_ITERATIONS && !ind.isCanceled(); attempt++) {
            ind.setText2("Iteration " + attempt);

            /* —— 1. Build context ———————————————————————————————— */
            ContextModel ctx = ReadAction.compute(() -> ContextExtractor.buildContext(cut));
            ctx.existingTestSource = testFile.get() == null ? null : testFile.get().getText();

            /* —— 2. Generate test code via LLM ———————————————— */
//            String prompt     = PromptBuilder.build(ctx);
            String testSource = "Sammple file";//JAIPilotLLM.invokeAI(prompt);

            /* —— 3. Write/update the file in the *single* testRoot —— */
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiFile existing = testFile.get();
                PsiFile newPsi;
                if (existing != null) {
                    PsiDocumentManager docMgr = PsiDocumentManager.getInstance(project);
                    var doc = docMgr.getDocument(existing);
                    if (doc != null) {
                        doc.setText(testSource);
                        docMgr.commitDocument(doc);
                        newPsi = existing;
                    } else {
                        existing.delete();
                        newPsi = PsiFileFactory.getInstance(project)
                                .createFileFromText(testFileName, JavaFileType.INSTANCE, testSource);
                        newPsi = (PsiFile) testRoot.add(newPsi);
                    }
                } else {
                    newPsi = PsiFileFactory.getInstance(project)
                            .createFileFromText(testFileName, JavaFileType.INSTANCE, testSource);
                    newPsi = (PsiFile) testRoot.add(newPsi);
                }
                JavaCodeStyleManager.getInstance(project).optimizeImports(newPsi);
                CodeStyleManager.getInstance(project).reformat(newPsi);
                testFile.set(newPsi);
            });

            /* —— 4. JaCoCo coverage ———————————————————————————————— */
            double ratio = CoverageJacocoUtil.runCoverageFor(project, cut);
            if (ratio >= TARGET_RATIO) {
                ind.setText2("Coverage " + (int) (ratio * 100) + "%  ✔ done");
                return;
            }
        }
    }

    private TestGenerationWorker() {}
}