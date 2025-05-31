package com.github.skrcode.javaautounittests;

import com.github.skrcode.javaautounittests.settings.AISettings;
import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.impl.source.PsiJavaFileImpl;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

import static com.github.skrcode.javaautounittests.PromptBuilder.buildPrompt;

public class GenerateTestAction extends AnAction implements DumbAware {

    // 1️⃣  Context-sensitive enable/disable so the item only shows for Java classes
    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        boolean isClass = element instanceof PsiClass;
        e.getPresentation().setEnabledAndVisible(isClass);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (!(psiFile instanceof PsiJavaFile)) {
            Messages.showErrorDialog(project, "Please select a Java class file.", "JAIPilot");
            return;
        }

        PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
        if (classes.length == 0) {
            Messages.showErrorDialog(project, "No class found in selected file.", "JAIPilot");
            return;
        }
        PsiClass testClass = classes[0]; // assume first class is the test class
        String packageName = ((PsiJavaFileImpl) psiFile).getPackageName().replace(".","/");

        // 3️⃣  Kick off background task
        ProgressManager.getInstance().run(new Task.Backgroundable(
                project,
                "Generating tests for " + testClass.getQualifiedName(),
                /* canBeCancelled = */ true) {

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                // --- STEP 1: gather source & context (read lock) ---
                indicator.setFraction(0.1);
                indicator.setText("Collecting class source…");
                String classSource = ReadAction.compute(() -> testClass.getText());

                // Suppose we have N heavy sub-tasks
                int totalSteps = 3;
                try {
                    processHeavy(classSource, indicator, totalSteps);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }

            @Override public void onSuccess() { /* UI updates here if needed */ }

            private void processHeavy(String src,
                                      ProgressIndicator indicator,
                                      int totalSteps) throws InterruptedException {

                // Example looped work: build prompt, call LLM, write file
                // 1. <Generating tests for class <full class path>
                // 2. <Calling AI model>
                // 3. <Cleaning up test class>
                // 4. <Saving test class to tests directory>
                // 5. <Test successfully generated for class <full class path>>
                // final on success message - test generated for 10 files.
                for (int step = 1; step <= totalSteps && !indicator.isCanceled(); step++) {
                    switch (step) {
                        case 1 -> {
                            indicator.setText("Analyzing dependencies…");
                            Thread.sleep(5000);
                            // …your code…
                        }
                        case 2 -> {
                            indicator.setText("Calling OpenAI…");
                            Thread.sleep(5000);
                            // …your code…
                        }
                        case 3 -> {
                            indicator.setText("Writing JUnit file…");
                            Thread.sleep(5000);
                            // …your code…
                        }
                    }
                    indicator.setFraction(step / (double) totalSteps);
                }
            }
        });

//       String outputResponseClass = invokeAI(buildPrompt(project,testClass, packageName));

//        FileWriterUtil.writeToFile(project, AISettings.getInstance().getTestDirectory()+"/"+packageName,testClass.getName()+"Test",outputResponseClass);
//        invokeAI();
//        runJUnitTestForClass(project, testClass);
    }

    private String invokeAI(String prompt) {
        try {
            OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(AISettings.getInstance().getOpenAiKey()).build();

            StructuredResponseCreateParams<ResponseOutput> params = ResponseCreateParams.builder()
                    .input(prompt)
                    .text(ResponseOutput.class)
                    .model(ChatModel.GPT_4_1_NANO)
                    .build();

            return client.responses().create(params).output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .map(responseTestClass -> responseTestClass.outputTestClass).collect(Collectors.joining());
    } catch (Throwable t) {
        t.printStackTrace();
        Messages.showErrorDialog("AI Error: " + t.getClass().getName() + "\n" + t.getMessage(), "LLM Error");
        return "ERROR: " + t.getMessage();
    }

    }

    private void runJUnitTestForClass(Project project, PsiClass psiClass) {
        JUnitConfigurationType type = JUnitConfigurationType.getInstance();
        RunManager runManager = RunManager.getInstance(project);

        RunnerAndConfigurationSettings settings = runManager.createConfiguration(
                psiClass.getName(), type.getConfigurationFactories()[0]);

        JUnitConfiguration configuration = (JUnitConfiguration) settings.getConfiguration();
        configuration.setModule(configuration.getConfigurationModule().getModule());
        configuration.setMainClass(psiClass);

        runManager.addConfiguration(settings);
        runManager.setSelectedConfiguration(settings);
        Executor executor = DefaultRunExecutor.getRunExecutorInstance();




//        ProgramRunnerUtil.executeConfiguration(settings, executor);
    }


}
//
//private void notifyResult(Project project, int linesDeleted) {
//    String title = "JAIPilot - AI Unit Test Generator<";
//    String msg = "";
//
//    NotificationGroup group = NotificationGroupManager.getInstance()
//            .getNotificationGroup("JAIPilot - AI Unit Test Generator Feedback");
//
//    Notification n = group.createNotification(title, msg, NotificationType.INFORMATION);
//
//    if (linesDeleted > 0) {
//        n.addAction(NotificationAction.createSimple("Rate in Marketplace",
//                () -> BrowserUtil.browse("https://plugins.jetbrains.com/intellij/com.github.skrcode.javaautounittests/review/new")));
//        n.addAction(NotificationAction.createSimpleExpiring("Later", () -> {}));
//    }
//    n.notify(project);
//}
