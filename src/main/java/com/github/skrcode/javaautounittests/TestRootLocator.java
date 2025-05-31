package com.github.skrcode.javaautounittests;

import com.intellij.ide.util.PackageUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Finds (or lazily creates) the corresponding <project>/src/test/java package that mirrors the
 * CUT's package. Uses {@link PackageUtil#findOrCreateDirectoryForPackage} instead of the
 * non‑existent {@code createPackage()} utility.
 */
public final class TestRootLocator {

    /** @return a writable PsiDirectory under *src/test/java* matching the source file's package. */
    public static @NotNull PsiDirectory getOrCreateTestRoot(Project project, PsiFile sourceFile) {
        PsiPackage srcPkg = JavaDirectoryService.getInstance()
                .getPackage(sourceFile.getContainingDirectory());
        if (srcPkg == null) throw new IllegalStateException("No package for source file");

        // 1 ▸ Try to locate an existing *test* source root that already has or can host the package
        ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            if (!index.isInTestSourceContent(root)) continue; // only test roots
            PsiDirectory dir = PsiManager.getInstance(project).findDirectory(root);
            if (dir == null) continue;

            PsiDirectory pkgDir = PackageUtil.findOrCreateDirectoryForPackage(
                    project, srcPkg.getQualifiedName(), dir, true);
            if (pkgDir != null) return pkgDir; // success
        }

        // 2 ▸ No test root? Create <repo>/src/test/java + package path.
        VirtualFile[] sourceRoots = ProjectRootManager.getInstance(project).getContentSourceRoots();
        if (sourceRoots.length == 0) throw new IllegalStateException("No content root available");

        VirtualFile mainRoot = sourceRoots[0];
        VirtualFile parent   = mainRoot.getParent();
        VirtualFile javaTestRoot;
        try {
            VirtualFile src  = parent.findChild("src");
            if (src == null) src = parent.createChildDirectory(project, "src");
            VirtualFile test = src.findChild("test");
            if (test == null) test = src.createChildDirectory(project, "test");
            VirtualFile java = test.findChild("java");
            if (java == null) java = test.createChildDirectory(project, "java");
            javaTestRoot = java;
        } catch (Exception e) { throw new RuntimeException(e); }

        PsiDirectory baseDir = PsiManager.getInstance(project).findDirectory(javaTestRoot);
        PsiDirectory pkgDir = PackageUtil.findOrCreateDirectoryForPackage(
                project, srcPkg.getQualifiedName(), baseDir, true);
        if (pkgDir == null) throw new IllegalStateException("Could not create test package directory");
        return pkgDir;
    }

    private TestRootLocator() {}
}