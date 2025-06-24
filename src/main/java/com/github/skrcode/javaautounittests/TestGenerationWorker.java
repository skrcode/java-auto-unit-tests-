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

    private static final int    MAX_ITERATIONS = 1;
    private static final double TARGET_RATIO   = 0.90;   // 90 %

    public static void process(Project project, PsiClass cut, @NotNull ProgressIndicator ind, PsiDirectory testRoot) {

        PsiDirectory packageDir = resolveTestPackageDir(project, testRoot, cut);
        if (packageDir == null) {
            ind.setText2("Cannot determine package for CUT");
            return;
        }

        String testFileName   = cut.getName() + "Test.java";
        String promptUrl      = "https://raw.githubusercontent.com/skrcode/java-auto-unit-tests/refs/heads/main/src/main/resources/base-prompt";
        String promptTemplate = PromptBuilder.loadPromptFromUrl(promptUrl);

        for(int compileAttempt = 0, executeAttempt = 0;
            compileAttempt < MAX_ITERATIONS && executeAttempt < MAX_ITERATIONS && !ind.isCanceled(); ) {

            int totalAttempts = compileAttempt + executeAttempt + 1;
            ind.setText2("Iteration " + totalAttempts);
            Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));
            ContextModel ctx = ReadAction.compute(() -> ContextExtractor.buildContext(cut));
            ctx.existingTestSource = testFile.get() == null ? null : testFile.get().getText();

            if(testFile.get() == null) {
                executeAIActionForAttempt(project, ctx, testFile, packageDir, promptTemplate, testFileName);
                compileAttempt++;
                continue;
            }

            PsiClass psiClass = getClassForExecution(testFile);

            String compileMsg = CoverageJacocoUtil.compileJUnitClass(project, psiClass);
            if (!compileMsg.isEmpty()) {
                ctx.errorMessage = compileMsg;
                executeAIActionForAttempt(project, ctx, testFile, packageDir, promptTemplate, testFileName);
                compileAttempt++;
                continue;
            }

            String executeMsg = CoverageJacocoUtil.executeJUnitClass(project, psiClass);
            if(!executeMsg.isEmpty()){
                ctx.errorMessage = executeMsg;
                executeAIActionForAttempt(project, ctx, testFile, packageDir, promptTemplate, testFileName);
                executeAttempt++;
                continue;
            }
            break;
//
//      outputCoverage = coverage(testclass)
//      if(outputCoverage < 90%)
//          testClass = genAI(baseclass,testclass,"",outputCoverage)
//          iterations = iterations + 1
//          write(testClass)
//	        continue
        }
    }

    private static void executeAIActionForAttempt(Project project, ContextModel ctx, Ref<PsiFile> testFile, PsiDirectory packageDir, String promptTemplate, String testFileName) {
        String prompt     = PromptBuilder.build(promptTemplate, ctx);
        String testSource = JAIPilotLLM.invokeAIGemini(prompt);
        write(project, testFile, testSource, packageDir, testFileName);
    }

    private static void write(Project project, Ref<PsiFile> testFile, String testSource, PsiDirectory packageDir, String testFileName) {
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
    }

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

    private static PsiClass getClassForExecution(Ref<PsiFile> testFile) {
        PsiFile psiFile = testFile.get();
        if (!(psiFile instanceof PsiClassOwner owner)) throw new IllegalArgumentException( "Class not found");

        PsiClass[] classes = owner.getClasses();
        if (classes.length == 0) throw new IllegalArgumentException("Class not found");
        return classes[0];
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
