package com.github.skrcode.javaautounittests;

import com.github.skrcode.javaautounittests.DTOs.ScenariosResponseOutput;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;


// open points
// 1. virtually compiling and executing tests
// 2. write all prompt - done
// 3. Parallelly compiling and executing tests
// 4. Parallelly calling prompts.
public final class TestGenerationWorker {

    private static final int    MAX_ITERATIONS = 5;
    private static final double TARGET_RATIO   = 0.90;

    private static String runScenarioPipeline(Project project, String testFileName, ScenariosResponseOutput.TestScenario testScenario, String singleTestPromptPlaceholder, String inputClass, String existingTestClass, PsiDirectory packageDir) {
        return runPipelineWithRetry(project, testFileName, testScenario,singleTestPromptPlaceholder,inputClass,"","", packageDir, 0);
    }

    private static String runPipelineWithRetry(Project project, String testFileName, ScenariosResponseOutput.TestScenario testScenario, String singleTestPromptPlaceholder, String inputClass, String existingTestClass, String errorOutput, PsiDirectory packageDir, int attempt) {
        final int MAX_ATTEMPTS = 5;
        String testClassCode = JAIPilotLLM.getSingleTest(singleTestPromptPlaceholder,testFileName,inputClass,testScenario, existingTestClass, errorOutput);
        Pair<String, String> executionResult = BuilderUtil.compileJUnitClass(project, testClassCode, testFileName, packageDir);
        if (executionResult.getSecond().isEmpty() || attempt >= MAX_ATTEMPTS) {
            return executionResult.getFirst();
        }
        return runPipelineWithRetry(project, testFileName, testScenario, singleTestPromptPlaceholder, inputClass,executionResult.getFirst(),executionResult.getSecond(),packageDir, attempt + 1);
//        return CompletableFuture
//                .supplyAsync(() -> JAIPilotLLM.getSingleTest(singleTestPromptPlaceholder,testFileName,inputClass,testScenario, existingTestClass, errorOutput), AppExecutorUtil.getAppExecutorService())
//                .thenApplyAsync(testClassCode -> BuilderUtil.compileJUnitClass(project, testClassCode, testFileName, packageDir), AppExecutorUtil.getAppExecutorService())
////                .thenApplyAsync(compilationResult -> BuilderUtil.executeJUnitClass(project, compilationResult, errorOutput, testFileName, packageDir), AppExecutorUtil.getAppExecutorService())
//                .thenCompose(executionResult -> {
//                    if (executionResult.getSecond().isEmpty() || attempt >= MAX_ATTEMPTS) {
//                        return CompletableFuture.completedFuture(executionResult.getFirst());
//                    }
//                    return runPipelineWithRetry(project, testFileName, testScenario, singleTestPromptPlaceholder, inputClass,executionResult.getFirst(),executionResult.getSecond(),packageDir, attempt + 1);
//                });
    }

    private static String  runAggregationPipeline(Project project, String testFileName, List<String> individualTestClasses, String aggregateTestClassPromptPlaceholder, String existingTestClass, PsiDirectory packageDir) {
        return runAggregationWithRetry(project, testFileName, individualTestClasses,aggregateTestClassPromptPlaceholder,existingTestClass,packageDir, "", 0);
    }

    private static String runAggregationWithRetry(Project project, String testFileName, List<String> individualTestClasses, String aggregateTestClassPromptPlaceholder, String existingTestClass, PsiDirectory packageDir, String errorOutput, int attempt) {
        final int MAX_ATTEMPTS = 5;
        String testClassCode = JAIPilotLLM.getAggregatedTests(aggregateTestClassPromptPlaceholder,existingTestClass,individualTestClasses, errorOutput);
        Pair<String, String> executionResult = BuilderUtil.compileJUnitClass(project, testClassCode, testFileName, packageDir);
        if (executionResult.getSecond().isEmpty() || attempt >= MAX_ATTEMPTS) {
            return executionResult.getFirst();
        }
        return runAggregationWithRetry(project, testFileName, new ArrayList<>(), aggregateTestClassPromptPlaceholder,executionResult.getFirst(),packageDir,executionResult.getSecond(), attempt + 1);

//                    return runAggregationWithRetry(project, testFileName, new ArrayList<>(), aggregateTestClassPromptPlaceholder,executionResult.getFirst(),packageDir,executionResult.getSecond(), attempt + 1);
//        return CompletableFuture
//                .supplyAsync(() -> JAIPilotLLM.getAggregatedTests(aggregateTestClassPromptPlaceholder,existingTestClass,individualTestClasses, errorOutput), AppExecutorUtil.getAppExecutorService())
//                .thenApplyAsync(testClassCode -> BuilderUtil.compileJUnitClass(project, testClassCode, testFileName, packageDir), AppExecutorUtil.getAppExecutorService())
////                .thenApplyAsync(compilationResult -> BuilderUtil.executeJUnitClass(project, compilationResult, errorOutput, testFileName, packageDir), AppExecutorUtil.getAppExecutorService())
//                .thenCompose(executionResult -> {
//                    if (executionResult.getSecond().isEmpty() || attempt >= MAX_ATTEMPTS) {
//                        return CompletableFuture.completedFuture(executionResult.getFirst());
//                    }
//                    return runAggregationWithRetry(project, testFileName, new ArrayList<>(), aggregateTestClassPromptPlaceholder,executionResult.getFirst(),packageDir,executionResult.getSecond(), attempt + 1);
//                });
    }

    public static void process(Project project, PsiClass cut, @NotNull ProgressIndicator ind, PsiDirectory testRoot) {

        PsiDirectory packageDir = resolveTestPackageDir(project, testRoot, cut);
        if (packageDir == null) {
            ind.setText2("Cannot determine package for CUT");
            return;
        }

        String testFileName = cut.getName() + "Test.java";
        Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));
        String existingTestClass = testFile.get() == null ? "" : testFile.get().getText();
        String cutClass = cut.getContainingFile().getText();
        String getScenariosPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-scenarios-prompt");
        String getSingleTestPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-single-test-prompt");
        String getAggregateTestClassPromptPlaceholder = PromptBuilder.getPromptPlaceholder("aggregate-test-class-prompt");
        ScenariosResponseOutput scenarios = JAIPilotLLM.getScenarios(getScenariosPromptPlaceholder, cutClass);
        List<String> additionalTestClasses = new ArrayList<>();
        for (int index = 0;index < 5; index++ ) {
            ScenariosResponseOutput.TestScenario testScenario = scenarios.testScenarios.get(index);
            try {
                additionalTestClasses.add(runScenarioPipeline(project, cut.getName() + "Test"+index+".java", testScenario, getSingleTestPromptPlaceholder, cutClass, existingTestClass, packageDir));
            } catch (Exception e) {
            }
        }
        try {
//            List<String> additionalTestClasses = CompletableFuture
//                    .allOf(futures.toArray(new CompletableFuture[0]))
//                    .thenApply(v -> futures.stream()
//                            .map(CompletableFuture::join)
//                            .collect(Collectors.toList()))
//                    .get(); // blocks until all are done
            String finalTestClassSource = runAggregationPipeline(project, testFileName, additionalTestClasses, getAggregateTestClassPromptPlaceholder, existingTestClass, packageDir);
//            String finalTestClassSource = finalTestClassSourceFuture.get();
//            String testSource = finalTestClassSourceFuture.get();
//            write(project, testFile, testSource, packageDir, testFileName);
        } catch (Exception e) {

        }


    }

//    private static void executeAIActionForAttempt(Project project, ContextModel ctx, Ref<PsiFile> testFile, PsiDirectory packageDir, String promptTemplate, String testFileName) {
//        String prompt     = PromptBuilder.build(promptTemplate, ctx);
//        String testSource = JAIPilotLLM.invokeAIGemini(prompt);
//        write(project, testFile, testSource, packageDir, testFileName);
//    }

    private static @Nullable PsiDirectory resolveTestPackageDir(Project project,
                                                                PsiDirectory testRoot,
                                                                PsiClass cut) {
        PsiPackage cutPkg = JavaDirectoryService.getInstance()
                .getPackage(cut.getContainingFile().getContainingDirectory());
        if (cutPkg == null) return null;

        String relPath = cutPkg.getQualifiedName().replace('.', '/');
        return getOrCreateSubdirectoryPath(project, testRoot, relPath);
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
