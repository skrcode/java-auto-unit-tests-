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

import java.util.HashSet;
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

        // 1. Create run config
        ConfigurationFactory factory = JUnitConfigurationType.getInstance().getConfigurationFactories()[0];
        RunnerAndConfigurationSettings settings = RunManager.getInstance(project).createConfiguration(testClass.getName(), factory);
        JUnitConfiguration config = (JUnitConfiguration) settings.getConfiguration();
        config.setModule(ModuleUtilCore.findModuleForPsiElement(testClass));
        config.setMainClass(testClass);

        // 2. Prepare execution environment with unique ID
        long executionId = System.nanoTime(); // practically guaranteed unique
        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        ProgramRunner<?> runner = ProgramRunner.getRunner(executor.getId(), config);
        if (runner == null) return "ERROR: No ProgramRunner found for config";

        ExecutionEnvironment env;
        try {
            env = ExecutionEnvironmentBuilder.create(project, executor, config)
                    .runnerAndSettings(runner, settings)
                    .build();
            env.setExecutionId(executionId); // ✅ Set unique execution ID
        } catch (ExecutionException e) {
            return "ERROR: Failed to build execution environment — " + e.getMessage();
        }

        // 3. Start the test
        ProgramRunnerUtil.executeConfiguration(env, false, false);

        // 4. Wait for matching handler using execution ID
        AtomicReference<ProcessHandler> handlerRef = new AtomicReference<>();
        CountDownLatch handlerReady = new CountDownLatch(1);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && handlerRef.get() == null) {
                for (RunContentDescriptor d : ExecutionManager.getInstance(project).getContentManager().getAllDescriptors()) {
                    if (d.getExecutionId() != executionId) continue;
                    ProcessHandler h = d.getProcessHandler();
                    if (h != null) {
                        handlerRef.set(h);
                        handlerReady.countDown();
                        return;
                    }
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            handlerReady.countDown(); // timeout path
        });

        try {
            if (!handlerReady.await(30, TimeUnit.SECONDS))
                return "ERROR: handler not found (timeout)";
        } catch (InterruptedException e) {
            return "ERROR: interrupted waiting for handler";
        }

        ProcessHandler handler = handlerRef.get();
        if (handler == null)
            return "ERROR: handler null (run aborted)";

        // 5. Capture output and wait for completion
        StringBuilder console = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        handler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                super.onTextAvailable(event, outputType);
                console.append(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                done.countDown();
            }
        });

        if (handler.isProcessTerminated()) done.countDown(); // skip wait if already terminated

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
}
