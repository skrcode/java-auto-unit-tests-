package com.github.skrcode.javaautounittests;

import com.github.skrcode.javaautounittests.DTOs.ScenariosResponseOutput;
import com.github.skrcode.javaautounittests.DTOs.SingleTestPromptResponseOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class TestGenerationWorker {

    private static final int MAX_ATTEMPTS= 10;// MAX_TESTS = 5;

    private static void  runAggregationPipeline(ProgressIndicator indicator, Project project, String testFileName, List<String> individualTestCases, String aggregateTestClassPromptPlaceholder, PsiDirectory packageDir) {
        runAggregationWithRetry(indicator, project, testFileName, individualTestCases, aggregateTestClassPromptPlaceholder,packageDir, "",0);
    }

    private static void runAggregationWithRetry(ProgressIndicator indicator, Project project, String testFileName, List<String> individualTestClasses, String aggregateTestClassPromptPlaceholder, PsiDirectory packageDir, String errorOutput, int attempt) {
        Ref<PsiFile> testFile = Ref.create(packageDir.findFile(testFileName));
        indicator.setText2("Aggregation - Trying attempt #"+attempt+": "+testFileName);
        String existingTestClass = testFile.get() == null ? "" : testFile.get().getText();
        indicator.setText2("Invoking LLM : "+testFileName);
        String testClassCode = JAIPilotLLM.getAggregatedTests(aggregateTestClassPromptPlaceholder,existingTestClass,testFileName,individualTestClasses, errorOutput);
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

        try {
            PsiDirectory packageDir = resolveTestPackageDir(project, testRoot, cut);
            if (packageDir == null) {
                indicator.setText("Cannot determine package for CUT");
                return;
            }

            String cutName = ReadAction.compute(() -> cut.isValid() ? cut.getName() : "<invalid>");
            String cutClass  = ReadAction.compute(() -> {
                if (!cut.isValid()) return "<invalid>";
                PsiFile file = cut.getContainingFile();
                return file != null ? file.getText() : "<no file>";
            });

            String getScenariosPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-scenarios-prompt");
            String getSingleTestPromptPlaceholder = PromptBuilder.getPromptPlaceholder("get-single-test-prompt");
            String getAggregateTestClassPromptPlaceholder = PromptBuilder.getPromptPlaceholder("aggregate-test-class-prompt");

            indicator.setText("Generating scenarios for " + cutName);
            ScenariosResponseOutput scenarios = JAIPilotLLM.getScenarios(getScenariosPromptPlaceholder, cutClass);

            int MAX_TESTS = scenarios.testScenarios.size();
            List<ScenariosResponseOutput.TestScenario> testableScenarios = scenarios.testScenarios.subList(0, Math.min(MAX_TESTS, scenarios.testScenarios.size()));
            Set<Integer> completedTests = new HashSet<>();
            List<String> individualTestCases = new ArrayList<>(), errorOutputOfindividualTestCases = new ArrayList<>(), existingIndividualTestClasses = new ArrayList<>(), individualTestFileNames = new ArrayList<>();
            List<List<String>> contextClassesForEachIndividualTest = new ArrayList<>();
            // Instantiate
            for (int index = 0; index < MAX_TESTS; index++) {
                individualTestCases.add("");
                errorOutputOfindividualTestCases.add("");
                individualTestFileNames.add(cutName + "TmpTest" + index + ".java");
                existingIndividualTestClasses.add("");
                contextClassesForEachIndividualTest.add(new ArrayList<>());
            }
            BuilderUtil.deleteFiles(project, individualTestFileNames, packageDir);

            // Attempts
            for (int attempt = 1; ; attempt++) {
                indicator.setText("Generating test : attempt" + attempt + "/" + MAX_ATTEMPTS);

                // 1. Build
                for (int index = 0; index < MAX_TESTS; index++) {
                    if (completedTests.contains(index)) continue;
                    Ref<PsiFile> individualTestFile = Ref.create(packageDir.findFile(individualTestFileNames.get(index)));
                    existingIndividualTestClasses.set(index, individualTestFile.get() == null ? "" : individualTestFile.get().getText());
                    if (individualTestFile.get() != null) {
                        indicator.setText("Compiling #" + attempt + "/" + MAX_ATTEMPTS + " : " + individualTestFileNames.get(index));
                        String existingIndividualTestClass = individualTestFile.get().getText();
                        String errorOutput = BuilderUtil.compileJUnitClass(project, individualTestFile);
                        if (errorOutput.isEmpty()) {
                            individualTestCases.set(index, existingIndividualTestClass);
                            completedTests.add(index);
                        }
                        errorOutputOfindividualTestCases.set(index, errorOutput);
                        indicator.setText("Compiled #" + attempt + "/" + MAX_ATTEMPTS + ": " + individualTestFileNames.get(index));
                    }
                }
                if (attempt > MAX_ATTEMPTS) break;
                // 2. Invoke LLM
                indicator.setText("Invoking LLM #" + attempt + "/" + MAX_ATTEMPTS);
                List<List<String>> contextClassesSourceForEachIndividualClass = contextClassesForEachIndividualTest.stream().map(
                        contextClassesForTest -> getSourceCodeOfContextClasses(project,contextClassesForTest)
                ).collect(Collectors.toList());

                SingleTestPromptResponseOutput singleTestPromptResponseOutput = JAIPilotLLM.getAllSingleTest(completedTests, getSingleTestPromptPlaceholder, individualTestFileNames, cutClass, testableScenarios, existingIndividualTestClasses, errorOutputOfindividualTestCases, contextClassesSourceForEachIndividualClass);
                indicator.setText("Successfully invoked LLM #" + attempt + "/" + MAX_ATTEMPTS);

                // 3. Write to temp files
                for (int index = 0; index < MAX_TESTS; index++) {
                    if (completedTests.contains(index)) continue;
                    indicator.setText("Writing to temp file LLM #" + attempt + "/" + MAX_ATTEMPTS + " : " + individualTestFileNames.get(index));
                    contextClassesForEachIndividualTest.set(index,singleTestPromptResponseOutput.getContextClassesForEachIndividualClass().get(index));
                    Ref<PsiFile> individualTestFile = Ref.create(packageDir.findFile(individualTestFileNames.get(index)));
                    BuilderUtil.write(project, individualTestFile, singleTestPromptResponseOutput.getTestClassCodeForEachIndividualClass().get(index), packageDir, individualTestFileNames.get(index));
                }
            }
            BuilderUtil.deleteFiles(project, individualTestFileNames, packageDir);

            String testFileName = cutName + "Test.java";
            indicator.setText("Aggregating Test Class " + testFileName);
            runAggregationPipeline(indicator, project, testFileName, individualTestCases, getAggregateTestClassPromptPlaceholder, packageDir);
            indicator.setText("Successfully generated Test Class " + testFileName);
        }
        catch (Throwable t) {
            t.printStackTrace();
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("Exception: " + t.getClass().getName() + "\n" + t.getMessage(), "Error")
            );
        }
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

    private static List<String> getSourceCodeOfContextClasses(Project project, List<String> contextClassesPath) {
        JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);

        List<String> result = new ArrayList<>();
        if(contextClassesPath == null) return result;
        for (String contextClassPath : contextClassesPath) {
            PsiClass psiClass = ReadAction.compute(() -> psiFacade.findClass(contextClassPath, scope));

            if (psiClass == null || !psiClass.isValid()) {
                result.add("");
                continue;
            }

            PsiFile file = psiClass.getContainingFile();
            if (file == null || !file.isValid()) {
                result.add("");
                continue;
            }
            String code = ReadAction.compute(file::getText);
            result.add(code);
        }

        return result;
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
