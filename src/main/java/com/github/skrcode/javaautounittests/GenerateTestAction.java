package com.github.skrcode.javaautounittests;

import com.github.skrcode.javaautounittests.settings.AISettings;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Entry‑point action – collects one or many classes/directories and delegates to the worker service.
 */
public class GenerateTestAction extends AnAction implements DumbAware {

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiElement elem = e.getData(CommonDataKeys.PSI_ELEMENT);
        boolean enable = elem instanceof PsiClass || elem instanceof PsiDirectory || elem instanceof PsiPackage;
        e.getPresentation().setEnabledAndVisible(enable);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiElement context = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (project == null || context == null) return;

        List<PsiClass> classes = collectClasses(context);
        if (classes.isEmpty()) {
            Messages.showErrorDialog(project, "No Java classes found in selection.", "JAIPilot");
            return;
        }
        BulkGeneratorService.enqueue(project, classes, stringPathToPsiDirectory(project,AISettings.getInstance().getTestDirectory()));
    }

    private static @Nullable PsiDirectory stringPathToPsiDirectory(Project project, String path) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        if (file == null || !file.isDirectory()) {
            return null;
        }
        return PsiManager.getInstance(project).findDirectory(file);
    }


    private List<PsiClass> collectClasses(PsiElement elem) {
        List<PsiClass> result = new ArrayList<>();
        if (elem instanceof PsiClass pc) {
            result.add(pc);
        } else if (elem instanceof PsiDirectory dir) {
            dir.accept(new JavaRecursiveElementVisitor() {
                @Override public void visitClass(PsiClass aClass) { result.add(aClass); }
            });
        } else if (elem instanceof PsiPackage pkg) {
            Collections.addAll(result, pkg.getClasses());
        }
        return result;
    }
}