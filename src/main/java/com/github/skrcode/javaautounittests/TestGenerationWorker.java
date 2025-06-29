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

    private static String runScenarioPipeline(ProgressIndicator indicator, Project project, String testFileName, ScenariosResponseOutput.TestScenario testScenario, String singleTestPromptPlaceholder, String inputClass, PsiDirectory packageDir) {
        return runPipelineWithRetry(indicator, project, testFileName, testScenario,singleTestPromptPlaceholder,inputClass, packageDir, 0);
    }

    private static String runPipelineWithRetry(ProgressIndicator indicator, Project project, String testFileName, ScenariosResponseOutput.TestScenario testScenario, String singleTestPromptPlaceholder, String inputClass, PsiDirectory packageDir, int attempt) {
        Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));
        indicator.setText2("Test case Generation - Trying attempt #"+attempt+": "+testFileName);
        String existingIndividualTestClass = "";
        String errorOutput = "";
        if(testFile.get() != null) {
            existingIndividualTestClass = testFile.get().getText();
            indicator.setText2("Compiling : "+testFileName);
            errorOutput = BuilderUtil.compileJUnitClass(project, testFile);
            indicator.setText2("Compiled : "+testFileName);
            if (errorOutput.isEmpty()) return existingIndividualTestClass;
            indicator.setText2("Errorred output - so retrying : "+testFileName);
            if (attempt >= MAX_ATTEMPTS) {
                indicator.setText2("Max attempts breached - test ignored : "+testFileName);
                BuilderUtil.deleteFile(project, testFile.get());
                return "";
            }
        }
        indicator.setText2("Invoking LLM : "+testFileName);
        String testClassCode = JAIPilotLLM.getSingleTest(singleTestPromptPlaceholder, testFileName, inputClass, testScenario, existingIndividualTestClass, errorOutput);
        indicator.setText2("Successfully invoked LLM : "+testFileName);
        BuilderUtil.write(project, testFile, testClassCode, packageDir, testFileName);
        indicator.setText2("Writing test to temp file : "+testFileName);
        return runPipelineWithRetry(indicator, project, testFileName, testScenario, singleTestPromptPlaceholder, inputClass,packageDir, attempt + 1);
    }

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

        String testFileName = cut.getName() + "Test.java";
        String cutClass = cut.getContainingFile().getText();
        String getScenariosPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-scenarios-prompt");
        String getSingleTestPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-single-test-prompt");
        String getAggregateTestClassPromptPlaceholder = PromptBuilder.getPromptPlaceholder("aggregate-test-class-prompt");

        indicator.setText("Generating scenarios for "+cutClass.getClass().getName());
        ScenariosResponseOutput scenarios = JAIPilotLLM.getScenarios(getScenariosPromptPlaceholder, cutClass);

        List<String> individualTestCases = new ArrayList<>();

        int MAX_TESTS = scenarios.testScenarios.size();
        for (int index = 1;index <= MAX_TESTS; index++ ) {
            String individualTestFileName = cut.getName() + "TmpTest"+index+".java";
            BuilderUtil.deleteFile(project, individualTestFileName, packageDir);
            ScenariosResponseOutput.TestScenario testScenario = scenarios.testScenarios.get(index);
            indicator.setText("Generating test "+index+"/"+MAX_TESTS+" : "+testScenario.toOneLiner());
            String individualTestFileCode = runScenarioPipeline(indicator,project,individualTestFileName,testScenario, getSingleTestPromptPlaceholder, cutClass, packageDir);
            individualTestCases.add(individualTestFileCode);
            BuilderUtil.deleteFile(project, individualTestFileName, packageDir);
            indicator.setText("Generated test "+index+"/"+MAX_TESTS+" : "+testScenario.toOneLiner());
        }
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
