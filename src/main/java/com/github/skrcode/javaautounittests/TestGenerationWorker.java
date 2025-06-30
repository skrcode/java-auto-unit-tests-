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

    private static final int MAX_ATTEMPTS= 5;// MAX_TESTS = 5;

    private static void  runAggregationPipeline(ProgressIndicator indicator, Project project, String testFileName, List<String> individualTestCases, String aggregateTestClassPromptPlaceholder, PsiDirectory packageDir) {
        runAggregationWithRetry(indicator, project, testFileName, individualTestCases, aggregateTestClassPromptPlaceholder,packageDir, "",0);
    }

    private static void runAggregationWithRetry(ProgressIndicator indicator, Project project, String testFileName, List<String> individualTestClasses, String aggregateTestClassPromptPlaceholder, PsiDirectory packageDir, String errorOutput, int attempt) {
        Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));
        indicator.setText2("Aggregation - Trying attempt #"+attempt+": "+testFileName);
        String existingTestClass = testFile.get() == null ? "" : testFile.get().getText();
        indicator.setText2("Invoking LLM : "+testFileName);
        String testClassCode = JAIPilotLLM.getAggregatedTests(aggregateTestClassPromptPlaceholder,existingTestClass,individualTestClasses, errorOutput);
        indicator.setText2("Successfully invoked LLM : "+testFileName);
        BuilderUtil.write(project,testFile,testClassCode,packageDir,testFileName);
        indicator.setText2("Writing test to file : "+testFileName);
        indicator.setText2("Compiling : "+testFileName);
        errorOutput = BuilderUtil.compileJUnitClass(project, testFile);
        indicator.setText2("Compiled : "+testFileName);
        if (errorOutput.isEmpty() || attempt >= MAX_ATTEMPTS) return;
        runAggregationWithRetry(indicator, project, testFileName, individualTestClasses, aggregateTestClassPromptPlaceholder,packageDir, errorOutput, attempt + 1);
    }

    public static void process(Project project, PsiClass cut, @NotNull ProgressIndicator indicator, PsiDirectory testRoot) {

        PsiDirectory packageDir = resolveTestPackageDir(project, testRoot, cut);
        if (packageDir == null) {
            indicator.setText("Cannot determine package for CUT");
            return;
        }

        String cutClass = cut.getContainingFile().getText();
        String getScenariosPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-scenarios-prompt");
        String getSingleTestPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-single-test-prompt");
        String getAggregateTestClassPromptPlaceholder = PromptBuilder.getPromptPlaceholder("aggregate-test-class-prompt");

        indicator.setText("Generating scenarios for "+cutClass.getClass().getName());
        ScenariosResponseOutput scenarios = JAIPilotLLM.getScenarios(getScenariosPromptPlaceholder, cutClass);

        int MAX_TESTS = 5;//scenarios.testScenarios.size();
        List<String> individualTestCases = new ArrayList<>(), errorOutputOfindividualTestCases = new ArrayList<>(), existingIndividualTestClasses = new ArrayList<>(), individualTestFileNames = new ArrayList<>();

        // Instantiate
        for (int index = 0;index < MAX_TESTS; index++ ) {
            individualTestCases.add("");
            errorOutputOfindividualTestCases.add("");
            individualTestFileNames.add(cut.getName() + "TmpTest" + index + ".java");
        }
        BuilderUtil.deleteFiles(project, individualTestFileNames, packageDir);
        // Attempts
        for(int attempt = 1;; attempt++) {
            indicator.setText("Generating test : attempt" + attempt + "/" + MAX_ATTEMPTS);

            // 1. Build
            for (int index = 0; index < MAX_TESTS; index++) {
                if(individualTestCases.get(index) != null) continue;
                Ref<PsiFile> individualTestFile = Ref.create(packageDir.findFile(individualTestFileNames.get(index)));
                existingIndividualTestClasses.add(individualTestFile.get() == null?"":individualTestFile.get().getText());
                if (individualTestFile.get() != null) {
                    indicator.setText("Compiling #" + attempt + "/" + MAX_ATTEMPTS + " : " + individualTestFileNames.get(index));
                    String existingIndividualTestClass = individualTestFile.get().getText();
                    String errorOutput = BuilderUtil.compileJUnitClass(project, individualTestFile);
                    if (errorOutput.isEmpty()) individualTestCases.set(index, existingIndividualTestClass);
                    errorOutputOfindividualTestCases.set(index, errorOutput);
                    indicator.setText("Compiled #" + attempt + "/" + MAX_ATTEMPTS + ": " + individualTestFileNames.get(index));
                }
            }
            if(attempt > MAX_ATTEMPTS) break;
            // 2. Invoke LLM
            indicator.setText("Invoking LLM #" + attempt + "/" + MAX_ATTEMPTS);
            List<String> allSingleTestClassCode = JAIPilotLLM.getAllSingleTest(getSingleTestPromptPlaceholder, individualTestFileNames, cutClass, scenarios.testScenarios, existingIndividualTestClasses, errorOutputOfindividualTestCases);
            indicator.setText("Successfully invoked LLM #" + attempt + "/" + MAX_ATTEMPTS);

            // 3. Write to temp files
            for (int index = 0; index < MAX_TESTS; index++) {
                if(individualTestCases.get(index) != null) continue;
                indicator.setText("Writing to temp file LLM #" + attempt + "/" + MAX_ATTEMPTS + " : " + individualTestFileNames.get(index));
                Ref<PsiFile> individualTestFile = Ref.create(packageDir.findFile(individualTestFileNames.get(index)));
                BuilderUtil.write(project, individualTestFile, allSingleTestClassCode.get(index), packageDir, individualTestFileNames.get(index));
            }
        }
        BuilderUtil.deleteFiles(project, individualTestFileNames, packageDir);

        String testFileName = cut.getName() + "Test.java";
        indicator.setText("Aggregating Test Class "+testFileName);
        runAggregationPipeline(indicator, project, testFileName,individualTestCases, getAggregateTestClassPromptPlaceholder, packageDir);
        indicator.setText("Successfully generated Test Class "+testFileName);
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
