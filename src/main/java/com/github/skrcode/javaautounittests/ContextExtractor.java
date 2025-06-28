package com.github.skrcode.javaautounittests;

import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;

public final class ContextExtractor {


    public static ContextModel buildContext(@NotNull PsiClass cut) {
        ContextModel ctx = new ContextModel();
        ctx.fullSource    = cut.getContainingFile().getText();
        return ctx;
    }

    private ContextExtractor() {}
}