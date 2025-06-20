package com.github.skrcode.javaautounittests;

import com.intellij.ide.util.PackageUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.io.IOException;

/**
 * Finds (or lazily creates) the corresponding <project>/src/test/java package that mirrors the
 * CUT's package. Uses {@link PackageUtil#findOrCreateDirectoryForPackage} instead of the
 * non‑existent {@code createPackage()} utility.
 */
public final class TestRootLocator {
    public static @NotNull PsiDirectory getOrCreateTestRoot(Project project, PsiFile sourceFile) {
        PsiDirectory sourceDir = sourceFile.getContainingDirectory();
        if (sourceDir == null)
            throw new IllegalStateException("Source file has no directory");

        PsiPackage psiPackage = JavaDirectoryService.getInstance().getPackage(sourceDir);
        if (psiPackage == null)
            throw new IllegalStateException("Cannot determine package for source file");

        String packageName = psiPackage.getQualifiedName();

        Module module = ModuleUtilCore.findModuleForPsiElement(sourceFile);
        if (module == null)
            throw new IllegalStateException("Source file is not part of any module");

        ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();

        // ✅ Primary: Find real test source roots in this module
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            if (!index.isInTestSourceContent(root)) continue;
            if (!module.equals(index.getModuleForFile(root))) continue;

            PsiDirectory rootDir = PsiManager.getInstance(project).findDirectory(root);
            if (rootDir == null) continue;

            PsiDirectory pkgDir = PackageUtil.findOrCreateDirectoryForPackage(
                    module, packageName, rootDir, true, true);
            if (pkgDir != null) return pkgDir;
        }
        // ❗Fallback: Try path replacement (main → test)
        VirtualFile sourceVF = sourceFile.getVirtualFile();
        if (sourceVF != null) {
            String testGuessPath = sourceVF.getPath().replace("/src/main/", "/src/test/");
            int lastSlash = testGuessPath.lastIndexOf('/');
            if (lastSlash > 0) testGuessPath = testGuessPath.substring(0, lastSlash); // remove filename

            VirtualFile guessedTestDir = LocalFileSystem.getInstance().findFileByPath(testGuessPath);
            if (guessedTestDir != null) {
                PsiDirectory fallbackDir = PsiManager.getInstance(project).findDirectory(guessedTestDir);
                if (fallbackDir != null) return fallbackDir;
            }
        }
        throw new IllegalStateException("Could not locate or create test directory for source file");
    }

    private TestRootLocator() {}
}