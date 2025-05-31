package com.github.skrcode.javaautounittests;

import com.intellij.psi.*;
import java.util.*;

/**
 * Converts a PSI class into the compact context we feed to the LLM.
 * Robust against constructors (no return‑type), synthetic bridge methods, etc.
 */
public final class ContextExtractor {

    public static ContextModel buildContext(PsiClass cut) {
        ContextModel ctx = new ContextModel();
        ctx.qualifiedName = cut.getQualifiedName();

        ctx.methods = Arrays.stream(cut.getMethods())
                .filter(method -> !method.isConstructor())                // skip ctors
                .filter(method -> !method.hasModifierProperty(PsiModifier.PRIVATE)) // we only care about externally‑visible API
                .filter(method -> !method.getName().startsWith("lambda$"))         // synthetic bridge methods
                .map(ContextExtractor::sig)
                .toList();

        ctx.dependencies = collectDeps(cut);
        return ctx;
    }

    /* ---------------- dependency extraction unchanged ---------------- */
    private static List<ContextModel.DependencyCall> collectDeps(PsiClass cut) {
        Map<String, Set<String>> byType = new HashMap<>();
        List<String> injected = new ArrayList<>();
        for (PsiField f : cut.getFields())
            injected.add(f.getType().getCanonicalText());
        for (PsiMethod ctor : cut.getConstructors())
            for (PsiParameter p : ctor.getParameterList().getParameters())
                injected.add(p.getType().getCanonicalText());

        cut.accept(new JavaRecursiveElementVisitor() {
            @Override public void visitMethodCallExpression(PsiMethodCallExpression expr) {
                PsiExpression q = expr.getMethodExpression().getQualifierExpression();
                if (q != null) {
                    PsiType t = q.getType();
                    if (t != null && injected.contains(t.getCanonicalText())) {
                        byType.computeIfAbsent(t.getPresentableText(), k -> new HashSet<>())
                                .add(expr.getMethodExpression().getReferenceName());
                    }
                }
            }
        });

        return byType.entrySet().stream().map(e -> {
            ContextModel.DependencyCall dc = new ContextModel.DependencyCall();
            dc.type = e.getKey();
            dc.methods = new ArrayList<>(e.getValue());
            return dc;
        }).toList();
    }

    /* ---------------- safe signature builder ---------------- */
    private static String sig(PsiMethod m) {
        String returnType = Optional.ofNullable(m.getReturnType())
                .map(PsiType::getPresentableText)
                .orElse("void");
        String params = Arrays.stream(m.getParameterList().getParameters())
                .map(p -> p.getType().getPresentableText())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return returnType + " " + m.getName() + "(" + params + ")";
    }

    private ContextExtractor() {}
}