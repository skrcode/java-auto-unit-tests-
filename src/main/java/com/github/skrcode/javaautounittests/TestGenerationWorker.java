package com.github.skrcode.javaautounittests;

import com.github.skrcode.javaautounittests.DTOs.ScenariosResponseOutput;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class TestGenerationWorker {

    private static final int MAX_ATTEMPTS= 5;

    private static String runScenarioPipeline(Project project, String testFileName, ScenariosResponseOutput.TestScenario testScenario, String singleTestPromptPlaceholder, String inputClass, PsiDirectory packageDir) {
        return runPipelineWithRetry(project, testFileName, testScenario,singleTestPromptPlaceholder,inputClass, packageDir, 0);
    }

    private static String runPipelineWithRetry(Project project, String testFileName, ScenariosResponseOutput.TestScenario testScenario, String singleTestPromptPlaceholder, String inputClass, PsiDirectory packageDir, int attempt) {
        Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));
        String existingIndividualTestClass = "";
        String errorOutput = "";
        if(testFile.get() != null) {
            existingIndividualTestClass = testFile.get().getText();
            errorOutput = BuilderUtil.compileJUnitClass(project, testFile);
            if (errorOutput.isEmpty() || attempt >= MAX_ATTEMPTS) return existingIndividualTestClass;
        }
        String testClassCode = JAIPilotLLM.getSingleTest(singleTestPromptPlaceholder, testFileName, inputClass, testScenario, existingIndividualTestClass, errorOutput);
        BuilderUtil.write(project, testFile, testClassCode, packageDir, testFileName);
        return runPipelineWithRetry(project, testFileName, testScenario, singleTestPromptPlaceholder, inputClass,packageDir, attempt + 1);
    }

    private static void  runAggregationPipeline(Project project, String testFileName, List<String> individualTestCases, String aggregateTestClassPromptPlaceholder, PsiDirectory packageDir) {
        runAggregationWithRetry(project, testFileName, individualTestCases, aggregateTestClassPromptPlaceholder,packageDir, "",0);
    }

    private static void runAggregationWithRetry(Project project, String testFileName, List<String> individualTestClasses, String aggregateTestClassPromptPlaceholder, PsiDirectory packageDir, String errorOutput, int attempt) {
        Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));
        String existingTestClass = testFile.get() == null ? "" : testFile.get().getText();
        String testClassCode = JAIPilotLLM.getAggregatedTests(aggregateTestClassPromptPlaceholder,existingTestClass,individualTestClasses, errorOutput);
        BuilderUtil.write(project,testFile,testClassCode,packageDir,testFileName);
        errorOutput = BuilderUtil.compileJUnitClass(project, testFile);
        if (errorOutput.isEmpty() || attempt >= MAX_ATTEMPTS) return;
        runAggregationWithRetry(project, testFileName, individualTestClasses, aggregateTestClassPromptPlaceholder,packageDir, errorOutput, attempt + 1);
    }

    public static void process(Project project, PsiClass cut, @NotNull ProgressIndicator ind, PsiDirectory testRoot) {

        PsiDirectory packageDir = resolveTestPackageDir(project, testRoot, cut);
        if (packageDir == null) {
            ind.setText2("Cannot determine package for CUT");
            return;
        }

        String testFileName = cut.getName() + "Test.java";
        String cutClass = cut.getContainingFile().getText();
        String getScenariosPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-scenarios-prompt");
        String getSingleTestPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-single-test-prompt");
        String getAggregateTestClassPromptPlaceholder = PromptBuilder.getPromptPlaceholder("aggregate-test-class-prompt");
        ScenariosResponseOutput scenarios = JAIPilotLLM.getScenarios(getScenariosPromptPlaceholder, cutClass);

        List<String> individualTestCases = new ArrayList<>();
        for (int index = 0;index < 5; index++ ) {
            String individualTestFileName = cut.getName() + "Test"+index+".java";
            ScenariosResponseOutput.TestScenario testScenario = scenarios.testScenarios.get(index);
            String individualTestFileCode = runScenarioPipeline(project,individualTestFileName,testScenario, getSingleTestPromptPlaceholder, cutClass, packageDir);
            individualTestCases.add(individualTestFileCode);
        }
        runAggregationPipeline(project, testFileName,individualTestCases, getAggregateTestClassPromptPlaceholder, packageDir);
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
