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
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.ide.DataManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compiles a JUnit test class, runs it with coverage, and returns:
 *   • ""  → everything succeeded
 *   • compilation errors if compilation failed
 *   • console output if test execution failed (non-zero exit or test failures)
 */
public class CoverageJacocoUtil {

    private CoverageJacocoUtil() {}

    public static @NotNull String executeJUnitClass(Project project, PsiClass testClass) {

        /* 1 – Build a transient JUnit run-configuration */
        RunnerAndConfigurationSettings settings =
                com.intellij.execution.RunManager.getInstance(project)
                        .createConfiguration(testClass.getName(),
                                JUnitConfigurationType.getInstance()
                                        .getConfigurationFactories()[0]);

        JUnitConfiguration cfg = (JUnitConfiguration) settings.getConfiguration();
        cfg.setModule(ModuleUtilCore.findModuleForPsiElement(testClass));
        cfg.setMainClass(testClass);

        /* 2 – Launch it exactly as IDE does */
        ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());

        /* 3 – Locate the process handler that just started */
        AtomicReference<ProcessHandler> handlerRef = new AtomicReference<>();
        CountDownLatch handlerReady = new CountDownLatch(1);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long deadline = System.currentTimeMillis() + 30_000;   // ≤30 s to discover handler
            while (System.currentTimeMillis() < deadline && handlerRef.get() == null) {
                for (RunContentDescriptor d :
                        ExecutionManager.getInstance(project).getContentManager().getAllDescriptors()) {
                    if (d.getDisplayName().contains(testClass.getName())) {
                        ProcessHandler h = d.getProcessHandler();
                        if (h != null) {
                            handlerRef.set(h);
                            handlerReady.countDown();
                            return;
                        }
                    }
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            handlerReady.countDown();   // release even if handler not found
        });

        try {
            if (!handlerReady.await(30, TimeUnit.SECONDS))
                return "ERROR: JUnit process handler not found";
        } catch (InterruptedException e) {
            return "ERROR: interrupted while waiting for handler";
        }

        ProcessHandler handler = handlerRef.get();
        if (handler == null)
            return "ERROR: handler null (run aborted before JVM start)";

        /* 4 – Capture console & wait for completion (≤5 min) */
        StringBuilder console = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        handler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                super.onTextAvailable(event, outputType);
                console.append(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent e) {
                done.countDown();
            }
        });

        try {
            if (!done.await(5, TimeUnit.MINUTES))
                return "ERROR: test execution timed out";
        } catch (InterruptedException e) {
            return "ERROR: interrupted while waiting for tests";
        }

        return Objects.equals(handler.getExitCode(), 0) ? "" : console.toString();
    }
    public static String compileJUnitClass(Project project, PsiClass testClass) {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        VirtualFile file = testClass.getContainingFile().getVirtualFile();

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



//    public static String compileAndRun(Project project, PsiClass testClass){
//        if (project == null || testClass == null || !testClass.isValid()) {
//            return "ERROR: invalid project or PsiClass";
//        }
//
//        /* ─────────────── 1. Compile whole project ─────────────── */
//        // Step 1: Compile
//        StringBuilder reply = new StringBuilder();
//        CountDownLatch compileLatch = new CountDownLatch(1);
//        VirtualFile fileToCompile = testClass.getContainingFile().getVirtualFile();
//
//        CompilerManager.getInstance(project).compile(
//                new VirtualFile[]{fileToCompile},
//                (boolean aborted, int errors, int warnings, com.intellij.openapi.compiler.CompileContext context) -> {
//                    if (aborted) {
//                        reply.append("COMPILATION_ABORTED");
//                    } else if (errors > 0) {
//                        reply.append("COMPILATION_FAILED\n");
//                        for (CompilerMessage msg : context.getMessages(CompilerMessageCategory.ERROR)) {
//                            reply.append(msg.getMessage()).append('\n');
//                        }
//                    }
//                    compileLatch.countDown();
//                }
//        );
//
//
//        await(compileLatch, 1); // wait up to 1 minute
//        if (reply.length() > 0) return reply.toString().trim();
//
//
//        /* ─────────────── 2. Build a JUnit run configuration ───── */
//        String qName = testClass.getQualifiedName();
//        JUnitConfigurationType type = JUnitConfigurationType.getInstance();
//        RunnerAndConfigurationSettings cfgSettings =
//                RunManager.getInstance(project)
//                        .createConfiguration(qName, type.getConfigurationFactories()[0]);
//
//        JUnitConfiguration cfg = (JUnitConfiguration) cfgSettings.getConfiguration();
//        cfg.setModule(ModuleUtilCore.findModuleForPsiElement(testClass));
//        cfg.setMainClass(testClass);
//
//        /* ─────────────── 3. Run & capture console ─────────────── */
//        CountDownLatch runLatch = new CountDownLatch(1);
//        final int[] exit = {0};
//
//
//        ExecutionEnvironment env = null;
//        try {
//            env = ExecutionEnvironmentBuilder
//                    .create(DefaultRunExecutor.getRunExecutorInstance(), cfgSettings) // ← NO Project arg
//                    .activeTarget()                       // optional but handy
//                    .build();
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        }
//
//        env.assignNewExecutionId();                   // mandatory when created programmatically
//
//        env.setCallback(descriptor -> {
//            ProcessHandler ph = descriptor.getProcessHandler();
//            if (ph == null) {
//                reply.append("RUN_ERROR: process handler is null");
//                runLatch.countDown();
//                return;
//            }
//            ph.addProcessListener(new ProcessAdapter() {
//
//
//                @Override
//                public void onTextAvailable(@NotNull ProcessEvent e, @NotNull Key outputType) {
//                    reply.append(e.getText());
//                }
//
//                @Override
//                public void processTerminated(ProcessEvent e) {
//                    exit[0] = e.getExitCode();
//                    runLatch.countDown();
//                }
//            });
//        });
//
//// 3) Launch the run configuration
//        ProgramRunnerUtil.executeConfiguration(env, /*showSettingsDialog*/ false, /*assignNewId*/ false);
//
//        await(runLatch, 1);               // ≤ 5 min test timeout
//
//        return exit[0] == 0 ? ""          // empty string ⇒ all green
//                : reply.toString().trim();
//    }

    /* Utility: wait for ≤ `minutes`, ignore interrupts but restore flag */
    private static void await(CountDownLatch latch, int minutes) {
        try {
            latch.await(minutes, TimeUnit.MINUTES);
        }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
