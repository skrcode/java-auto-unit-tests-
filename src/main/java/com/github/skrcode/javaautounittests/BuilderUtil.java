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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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


    public static @NotNull String executeJUnitClass(Project project, PsiClass testClass) {

        // ── shared state (safe to create off-EDT) ────────────────────────────────
        StringBuilder  failures = new StringBuilder();
        CountDownLatch done     = new CountDownLatch(1);
        Pattern TEAMCITY_FAIL   = Pattern.compile("^##teamcity\\[testFailed");

        ApplicationManager.getApplication().invokeAndWait(() -> {

            // 1 ── flush unsaved edits *on the EDT*
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            FileDocumentManager.getInstance().saveAllDocuments();

            // 2 ── build a transient JUnit run-config
            ConfigurationFactory factory =
                    JUnitConfigurationType.getInstance().getConfigurationFactories()[0];

            RunnerAndConfigurationSettings settings =
                    RunManager.getInstance(project).createConfiguration(testClass.getName(), factory);

            JUnitConfiguration cfg = (JUnitConfiguration) settings.getConfiguration();
            cfg.setModule(ModuleUtilCore.findModuleForPsiElement(testClass));
            cfg.setMainClass(testClass);

            Executor executor = DefaultRunExecutor.getRunExecutorInstance();

            // 3 ── hook a listener that fires when *this* run starts
            MessageBusConnection conn = project.getMessageBus().connect();
            conn.subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
                @Override
                public void processStarted(@NotNull String executorId,
                                           @NotNull ExecutionEnvironment env,
                                           @NotNull ProcessHandler handler) {
                    if (env.getRunProfile() != cfg) return;   // not our run

                    handler.addProcessListener(new ProcessAdapter() {
                        @Override
                        public void onTextAvailable(@NotNull ProcessEvent e, @NotNull Key outputType) {
                            String txt = e.getText().trim();
                            if (TEAMCITY_FAIL.matcher(txt).find()) {
                                failures.append(txt.replace("|n", "\n")
                                                .replace("|r", "\r"))
                                        .append('\n');
                            }
                        }

                        @Override
                        public void processTerminated(@NotNull ProcessEvent e) {
                            done.countDown();
                            conn.disconnect();
                        }
                    });
                }

                @Override
                public void processNotStarted(@NotNull String executorId,
                                              @NotNull ExecutionEnvironment env) {
                    if (env.getRunProfile() == cfg) {
                        failures.append("ERROR: test JVM did not start\n");
                        done.countDown();
                        conn.disconnect();
                    }
                }
            });

            // 4 ── launch (2-arg overload that exists in your SDK)
            ExecutionUtil.runConfiguration(settings, executor);
        });

        // 5 ── wait ≤ 5 min for test JVM to exit
        try {
            if (!done.await(5, TimeUnit.MINUTES))
                return "ERROR: test execution timed out";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "ERROR: interrupted while waiting for tests";
        }

        return failures.length() == 0 ? "" : failures.toString();
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

    public static void deleteFile(Project project, String fileNameToDelete, PsiDirectory packageDir) {
        Ref<PsiFile> fileToDelete = Ref.create(packageDir.findFile(fileNameToDelete));
        if (fileToDelete.get() == null) return;

        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                fileToDelete.get().delete();
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
