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

    public static void process(Project project, PsiClass cut, @NotNull ProgressIndicator ind, PsiDirectory testRoot) {
        String testFileName = cut.getName() + "Test.java";
        String outputMessage = "";
        String promptUrl = "https://raw.githubusercontent.com/skrcode/java-auto-unit-tests/refs/heads/main/src/main/resources/base-prompt";
        String promptTemplate = PromptBuilder.loadPromptFromUrl(promptUrl);
        for (int attempt = 1; attempt <= MAX_ITERATIONS && !ind.isCanceled(); attempt++) {

            Ref<PsiFile> testFile = Ref.create(testRoot.findFile(testFileName));
            ind.setText2("Iteration " + attempt);

            ContextModel ctx = ReadAction.compute(() -> ContextExtractor.buildContext(cut));
            ctx.existingTestSource = testFile.get() == null ? null : testFile.get().getText();

            String testSource;
            PsiFile existing = testFile.get();
            if (existing != null) {
                PsiDocumentManager docMgr = PsiDocumentManager.getInstance(project);
                var doc = docMgr.getDocument(existing);
                if (doc != null) {
                    outputMessage = executeCompile(project, testFile);
                    if(outputMessage.length() == 0) break;
                }
                ctx.errorMessage = outputMessage;
            }
            String prompt = PromptBuilder.build(promptTemplate,ctx);
            testSource = JAIPilotLLM.invokeAI(prompt);

            /* —— 3. Write/update the file in the *single* testRoot —— */
            WriteCommandAction.runWriteCommandAction(project, () -> {
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
                testFile.set(existing);
            });

            /* —— 4. JaCoCo coverage ———————————————————————————————— */

//            if (ratio >= TARGET_RATIO) {
//                ind.setText2("Coverage " + (int) (ratio * 100) + "%  ✔ done");
//                return;
//            }
        }
    }

    private static String executeCompile(Project project, Ref<PsiFile> testFile) {
        PsiFile psiFile = testFile.get();
        if (psiFile instanceof PsiClassOwner) {
            PsiClass[] classes = ((PsiClassOwner) psiFile).getClasses();
            if (classes.length > 0) {
                PsiClass testClass = classes[0];  // First top-level class
                return CoverageJacocoUtil.compileJUnitClass(project, testClass);
            }
            return "Class not found";
        }
        return "Class not found";
    }

    private TestGenerationWorker() {}
}