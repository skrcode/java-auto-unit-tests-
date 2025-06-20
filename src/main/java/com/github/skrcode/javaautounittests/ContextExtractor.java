package com.github.skrcode.javaautounittests;

import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;

/** Stripped-down extractor – no explicit dependencies list. */
public final class ContextExtractor {

    /* ---------------- PUBLIC API ---------------- */

    public static ContextModel buildContext(@NotNull PsiClass cut) {
        ContextModel ctx = new ContextModel();
        ctx.qualifiedName = cut.getQualifiedName();
        ctx.fullSource    = cut.getContainingFile().getText();
        return ctx;
    }

    private ContextExtractor() {}
}