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

    public static @NotNull Pair<String, String> executeJUnitClass(Project project, String testClassCode, String errorOutputCompilation, String testClassName, PsiDirectory packageDir) {
        if(!errorOutputCompilation.isEmpty()) {
            return Pair.create(testClassCode, errorOutputCompilation);
        }
        StringBuilder failures = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        Pattern TEAMCITY_FAIL = Pattern.compile("^##teamcity\\[testFailed");

        // 1. Create LightVirtualFile and PsiClass
        LightVirtualFile file = new LightVirtualFile(testClassName + ".java", JavaFileType.INSTANCE, testClassCode);
        PsiJavaFile psiFile = (PsiJavaFile) PsiManager.getInstance(project).findFile(file);
        if (psiFile == null || psiFile.getClasses().length == 0) {
            return Pair.create(testClassCode, "ERROR: Could not parse class");
        }
        PsiClass testClass = psiFile.getClasses()[0];

        // 2. Flush documents on EDT
        ApplicationManager.getApplication().invokeAndWait(() -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            FileDocumentManager.getInstance().saveAllDocuments();
        });

        // 3. Build transient JUnit config
        RunManager runManager = RunManager.getInstance(project);
        ConfigurationFactory factory = JUnitConfigurationType.getInstance().getConfigurationFactories()[0];
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(testClassName, factory);

        // Mark it as temporary so the run config UI doesn't appear
        settings.setTemporary(true);
        runManager.setTemporaryConfiguration(settings);

        JUnitConfiguration cfg = (JUnitConfiguration) settings.getConfiguration();
        cfg.setMainClass(testClass);

        // Resolve module from the test directory (safe fallback check)
        @Nullable Module module = ModuleUtilCore.findModuleForPsiElement(packageDir);
        if (module == null) {
            throw new IllegalStateException("Cannot determine module for test directory: " + packageDir.getVirtualFile().getPath());
        }
        cfg.setModule(module);

        Executor executor = DefaultRunExecutor.getRunExecutorInstance();

        // 4. Hook into execution
        MessageBusConnection conn = project.getMessageBus().connect();
        conn.subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
                if (env.getRunProfile() != cfg) return;

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
                        done.countDown();
                        conn.disconnect();
                    }
                });
            }

            @Override
            public void processNotStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env) {
                if (env.getRunProfile() == cfg) {
                    failures.append("ERROR: test JVM did not start\n");
                    done.countDown();
                    conn.disconnect();
                }
            }
        });

        // 5. Launch the test
        ApplicationManager.getApplication().invokeLater(() ->
                ExecutionUtil.runConfiguration(settings, executor)
        );

        // 6. Wait for completion
        try {
            if (!done.await(5, TimeUnit.MINUTES)) {
                return Pair.create(testClassCode,"ERROR: test execution timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Pair.create(testClassCode,"ERROR: interrupted while waiting for tests");
        }

        return Pair.create(testClassCode,failures.length() == 0 ? "" : failures.toString());
    }


    public static Pair<String,String> compileJUnitClass(Project      project,
                                                         String       testClassCode,
                                                         String       fileName,      // e.g. "MyTest.java"
                                                         PsiDirectory targetDir)  {
        Ref<PsiFile> testFile = Ref.create(targetDir.findFile(fileName));
        write(project,testFile,testClassCode,targetDir,fileName);
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

        return Pair.create(testClassCode, result.toString().trim());
    }


//    public static Pair<String, String> compileJUnitClass(Project      project,
//                                                         String       testClassCode,
//                                                         String       fileName,      // e.g. "MyTest.java"
//                                                         PsiDirectory targetDir) {
//        if (targetDir == null)
//            return Pair.create(testClassCode, "ERROR: targetDir is null");
//
//        /* ── 0 – must run off the EDT ─────────────────────────────────────────── */
//        if (ApplicationManager.getApplication().isDispatchThread())
//            throw new IllegalStateException("compileJUnitClass must be called off the EDT");
//
//        /* ── 1 – create / overwrite the PsiFile inside a write-action ─────────── */
//        AtomicReference<PsiFile> fileRef = new AtomicReference<>();
//        String writeError = ApplicationManager.getApplication().runWriteAction((Computable<String>) () -> {
//            try {
//                PsiFile psi = targetDir.findFile(fileName);
//                if (psi == null) {
//                    psi = PsiFileFactory.getInstance(project)
//                            .createFileFromText(fileName, JavaFileType.INSTANCE, testClassCode);
//                    targetDir.add(psi);
//                } else {
//                    Document doc = PsiDocumentManager.getInstance(project).getDocument(psi);
//                    if (doc != null) doc.setText(testClassCode);
//                }
//                JavaCodeStyleManager.getInstance(project).optimizeImports(psi);
//                CodeStyleManager.getInstance(project).reformat(psi);
//                fileRef.set(psi);
//                return null;          // success
//            } catch (Exception ex) {
//                return "ERROR: cannot write file – " + ex.getMessage();
//            }
//        });
//        if (writeError != null)
//            return Pair.create(testClassCode, writeError);
//
//        /* ── 2 – commit / save so the compiler sees fresh text ────────────────── */
//        ApplicationManager.getApplication().invokeAndWait(() -> {
//            PsiDocumentManager.getInstance(project).commitAllDocuments();
//            FileDocumentManager.getInstance().saveAllDocuments();
//        });
//
//        /* ── 3 – wait for any current build to finish ─────────────────────────── */
//        CompilerManager cm = CompilerManager.getInstance(project);
//        while (cm.isCompilationActive()) {
//            try { Thread.sleep(100); } catch (InterruptedException ie) {
//                Thread.currentThread().interrupt();
//                return Pair.create(testClassCode, "COMPILATION_INTERRUPTED");
//            }
//        }
//
//        /* ── 4 – compile just this file and block until done ──────────────────── */
//        StringBuilder err = new StringBuilder();
//        CountDownLatch latch = new CountDownLatch(1);
//
//        CompileScope scope = cm.createFilesCompileScope(new PsiFile[]{fileRef.get()});
//        cm.compile(scope, (aborted, errors, warnings, ctx) -> {
//            if (aborted) {
//                err.append("COMPILATION_ABORTED");
//            } else if (errors > 0) {
//                err.append("COMPILATION_FAILED\n");
//                for (CompilerMessage m : ctx.getMessages(CompilerMessageCategory.ERROR))
//                    err.append(m.getMessage()).append('\n');
//            }
//            latch.countDown();
//        });
//
//        try {
//            if (!latch.await(60, TimeUnit.SECONDS))
//                err.append("COMPILATION_TIMEOUT");
//        } catch (InterruptedException ie) {
//            Thread.currentThread().interrupt();
//            err.append("COMPILATION_INTERRUPTED");
//        }
//
//        /* ── 5 – return the final (re-formatted) source + any errors ──────────── */
//        return Pair.create(fileRef.get().getText(), err.toString().trim());
//    }

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


    private static PsiFile createAndAddFile(Project project,
                                            PsiDirectory dir,
                                            String name,
                                            String source) {
        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText(name, JavaFileType.INSTANCE, source);
        return (PsiFile) dir.add(file);
    }






}
