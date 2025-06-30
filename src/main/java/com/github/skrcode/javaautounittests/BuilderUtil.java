package com.github.skrcode.javaautounittests;
import com.intellij.execution.*;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentFolder;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Compiles a JUnit test class, runs it with coverage, and returns:
 *   • ""  → everything succeeded
 *   • compilation errors if compilation failed
 *   • console output if test execution failed (non-zero exit or test failures)
 */
public class BuilderUtil {

    private BuilderUtil() {}


    public static @NotNull String executeJUnitClass(Project project, Ref<PsiFile> testFileRef) {
        StringBuilder failures = new StringBuilder();
        Pattern TEAMCITY_FAIL = Pattern.compile("^##teamcity\\[testFailed");

        PsiFile psiFile = testFileRef.get();
        if (!(psiFile instanceof PsiJavaFile)) {
            return "ERROR: Not a Java file";
        }

        PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
        if (classes.length == 0) {
            return "ERROR: No class found in file";
        }

        PsiClass testClass = classes[0];

        ApplicationManager.getApplication().invokeAndWait(() -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            FileDocumentManager.getInstance().saveAllDocuments();
        });

        // ── 1 – Create config ─────────────────────────────────────
        ConfigurationFactory factory = JUnitConfigurationType.getInstance().getConfigurationFactories()[0];
        RunnerAndConfigurationSettings settings =
                RunManager.getInstance(project).createConfiguration(testClass.getName(), factory);

        JUnitConfiguration config = (JUnitConfiguration) settings.getConfiguration();
        config.setModule(ModuleUtilCore.findModuleForPsiElement(testClass));
        config.setMainClass(testClass);

        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        AtomicBoolean processStarted = new AtomicBoolean(false);

        // ── 2 – Run & wait ────────────────────────────────────────
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                ExecutionEnvironmentBuilder builder = ExecutionEnvironmentBuilder.create(executor, settings);

                ExecutionEnvironment environment = builder.build();

                environment.setCallback(descriptor -> {
                    ProcessHandler handler = descriptor.getProcessHandler();
                    if (handler == null) {
                        failures.append("ERROR: No process handler\n");
                        return;
                    }

                    processStarted.set(true);

                    handler.addProcessListener(new ProcessAdapter() {
                        @Override
                        public void onTextAvailable(@NotNull ProcessEvent e, @NotNull Key outputType) {
                            String txt = e.getText().trim();
                            if (TEAMCITY_FAIL.matcher(txt).find()) {
                                failures.append(txt.replace("|n", "\n").replace("|r", "\r")).append('\n');
                            }
                        }

                        @Override
                        public void processTerminated(@NotNull ProcessEvent e) {
                            // nothing to do here – the invokeAndWait already blocks till callback ends
                        }
                    });

                    handler.startNotify(); // starts the process listener
                });

                ProgramRunnerUtil.executeConfiguration(environment, false, true); // block until run completes

            } catch (Throwable t) {
                failures.append("ERROR: could not launch test – ").append(t.getMessage()).append('\n');
            }
        });

        if (!processStarted.get()) {
            return "ERROR: Test JVM did not start";
        }

        return failures.toString().trim();
    }




    public static String compileJUnitClass(Project project, Ref<PsiFile> testFile)  {

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        VirtualFile file = testFile.get().getVirtualFile();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            CompilerManager.getInstance(project).compile(new VirtualFile[]{file}, (aborted, errors, warnings, context) -> {
                if (aborted) {
                    result.append("COMPILATION_ABORTED");
                } else if (errors > 0) {
                    result.append("COMPILATION_FAILED\n");
                    for (CompilerMessage msg : context.getMessages(CompilerMessageCategory.ERROR)) {
                        result.append(msg.getMessage()).append('\n');
                    }
                }
                latch.countDown();
            });
        });

        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                result.append("COMPILATION_TIMEOUT");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.append("COMPILATION_INTERRUPTED");
        }

        return result.toString().trim();
    }

    public static void write(Project project, Ref<PsiFile> testFile, String testSource, PsiDirectory packageDir, String testFileName) {
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

    public static void deleteFile(Project project, PsiFile fileToDelete) {
        if (fileToDelete == null) return;

        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                fileToDelete.delete();
            } catch (Exception e) {
                // Optionally log or show notification
                e.printStackTrace();
            }
        });
    }

    public static void deleteFiles(Project project, List<String> fileNamesToDelete, PsiDirectory packageDir) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                for(String fileNameToDelete: fileNamesToDelete) {
                    Ref<PsiFile> fileToDelete = Ref.create(packageDir.findFile(fileNameToDelete));
                    if (fileToDelete.get() == null) continue;
                    fileToDelete.get().delete();
                }
            } catch (Exception e) {
                // Optionally log or show notification
                e.printStackTrace();
            }
        });
    }


    private static PsiFile createAndAddFile(Project project,
                                            PsiDirectory dir,
                                            String name,
                                            String source) {
        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText(name, JavaFileType.INSTANCE, source);
        return (PsiFile) dir.add(file);
    }






}
