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
import org.jetbrains.annotations.Nullable;

public final class TestGenerationWorker {

    private static final int    MAX_ITERATIONS = 5;
    private static final double TARGET_RATIO   = 0.90;   // 90 %

    /**
     * @param testRoot the *source-root* for tests (e.g. .../src/test/java).
     */
    public static void process(Project project,
                               PsiClass cut,
                               @NotNull ProgressIndicator ind,
                               PsiDirectory testRoot) {

        // ── 0. Resolve / create the package-matching directory inside testRoot ────────────
        PsiDirectory packageDir = resolveTestPackageDir(project, testRoot, cut);
        if (packageDir == null) {
            ind.setText2("Cannot determine package for CUT");
            return;
        }

        String testFileName   = cut.getName() + "Test.java";
        String promptUrl      = "https://raw.githubusercontent.com/skrcode/java-auto-unit-tests/refs/heads/main/src/main/resources/base-prompt";
        String promptTemplate = PromptBuilder.loadPromptFromUrl(promptUrl);

        for (int attempt = 1; attempt <= MAX_ITERATIONS && !ind.isCanceled(); attempt++) {
            ind.setText2("Iteration " + attempt);

            Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));

            // ── 1. Build context (read-action) ───────────────────────────────────────────
            ContextModel ctx = ReadAction.compute(() -> ContextExtractor.buildContext(cut));
            ctx.existingTestSource = testFile.get() == null ? null : testFile.get().getText();

            // If we already have a file, try to compile it first; bail out if compilation passes.
            if (testFile.get() != null) {
                String compileMsg = executeCompile(project, testFile);
                if (compileMsg.isEmpty()) {
                    // TODO: collect coverage here, break early if ≥ TARGET_RATIO
                    break;
                }
                ctx.errorMessage = compileMsg;          // feed errors back to the LLM
            }

            // ── 2. Invoke LLM to (re)generate test source ───────────────────────────────
            String prompt     = PromptBuilder.build(promptTemplate, ctx);
            String testSource = JAIPilotLLM.invokeAI(prompt);

            // ── 3. Write / update PSI file in one EDT write-action ───────────────────────
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiFile newPsi;
                if (testFile.get() != null) {           // update existing
                    PsiDocumentManager docMgr = PsiDocumentManager.getInstance(project);
                    var doc = docMgr.getDocument(testFile.get());
                    if (doc != null) {
                        doc.setText(testSource);
                        docMgr.commitDocument(doc);
                        newPsi = testFile.get();
                    } else {                            // fallback: replace file completely
                        testFile.get().delete();
                        newPsi = createAndAddFile(project, packageDir, testFileName, testSource);
                    }
                } else {                                // create fresh file
                    newPsi = createAndAddFile(project, packageDir, testFileName, testSource);
                }
                JavaCodeStyleManager.getInstance(project).optimizeImports(newPsi);
                CodeStyleManager.getInstance(project).reformat(newPsi);
                testFile.set(newPsi);                   // 🔑 update the Ref to the NEW file
            });

            // ── 4. Compile and (optionally) measure coverage ────────────────────────────
            String compileMsg = executeCompile(project, testFile);
            if (!compileMsg.isEmpty()) ctx.errorMessage = compileMsg;

            // TODO: collect JaCoCo coverage and break when ratio ≥ TARGET_RATIO
        }
    }

    /* ────────────────────────────────────────────────────────────────────────────── */

    private static @Nullable PsiDirectory resolveTestPackageDir(Project project,
                                                                PsiDirectory testRoot,
                                                                PsiClass cut) {
        PsiPackage cutPkg = JavaDirectoryService.getInstance()
                .getPackage(cut.getContainingFile().getContainingDirectory());
        if (cutPkg == null) return null;

        String relPath = cutPkg.getQualifiedName().replace('.', '/');
        return getOrCreateSubdirectoryPath(project, testRoot, relPath);
    }

    private static PsiFile createAndAddFile(Project project,
                                            PsiDirectory dir,
                                            String name,
                                            String source) {
        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText(name, JavaFileType.INSTANCE, source);
        return (PsiFile) dir.add(file);
    }

    private static String executeCompile(Project project, Ref<PsiFile> testFile) {
        PsiFile psiFile = testFile.get();
        if (!(psiFile instanceof PsiClassOwner owner)) return "Class not found";

        PsiClass[] classes = owner.getClasses();
        if (classes.length == 0) return "Class not found";

        return CoverageJacocoUtil.compileJUnitClass(project, classes[0]); // your util
    }

    /** Recursively find or create nested sub-directories like {@code org/example/service}. */
    private static @Nullable PsiDirectory getOrCreateSubdirectoryPath(Project project,
                                                                      PsiDirectory root,
                                                                      String relativePath) {
        return WriteCommandAction.writeCommandAction(project).compute(() -> {
            PsiDirectory current = root;
            for (String part : relativePath.split("/")) {
                PsiDirectory next = current.findSubdirectory(part);
                if (next == null) next = current.createSubdirectory(part);
                current = next;
            }
            return current;
        });
    }

    private TestGenerationWorker() {} // no-instantiation
}
