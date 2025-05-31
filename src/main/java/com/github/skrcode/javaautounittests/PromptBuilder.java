package com.github.skrcode.javaautounittests;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class PromptBuilder {

    public static String buildPrompt(Project project, PsiClass targetClass, String packageName) {
        StringBuilder sb = new StringBuilder();

        // 1. Full source of the right-clicked class
        sb.append("You are a Java test-generation assistant. Generate high quality robust unit tests. Output is only the test class. Include all imports and class package. Follow test method name convention - test<ClassName>_<Scenario>_<ResponseType>. Cover a negative edge case when possible.");
        sb.append("// Test Class Package\n");
        sb.append(packageName);
        sb.append("// Class Import\n");
        sb.append(packageName+"."+targetClass.getName());
        sb.append("// Class\n");
        sb.append(targetClass.getText()).append("\n\n");

        // 2. Method call dependencies
        sb.append("// Method Dependencies\n");
        Set<String> methodCalls = collectMethodCallSignatures(targetClass);
        for (String sig : methodCalls) {
            sb.append(sig).append("\n");
        }

        // 3. Input/output classes used in public API
        sb.append("\n// POJOs \n");
        Set<PsiClass> ioClasses = collectIOClasses(project, targetClass);
        for (PsiClass ioClass : ioClasses) {
            sb.append("// Class: ").append(ioClass.getQualifiedName()).append("\n");
            sb.append(ioClass.getText()).append("\n\n");
        }

        return sb.toString();
    }

    private static Set<String> collectMethodCallSignatures(PsiClass psiClass) {
        Set<String> calls = new LinkedHashSet<>();
        psiClass.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod method = expression.resolveMethod();
                if (method != null && method.getContainingClass() != null) {
                    calls.add(method.getContainingClass().getQualifiedName() + "#" + method.getName()
                            + method.getParameterList().getText());
                }
            }
        });
        return calls;
    }

    public static Set<PsiClass> collectIOClasses(Project project, PsiClass psiClass) {
        Set<PsiClass> result = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);

        for (PsiMethod method : psiClass.getMethods()) {
            // Inputs
            for (PsiParameter param : method.getParameterList().getParameters()) {
                collectFromType(project, param.getType(), result, visited, scope);
            }
            // Output
            collectFromType(project, method.getReturnType(), result, visited, scope);
        }

        return result;
    }

    private static void collectFromType(Project project, PsiType type, Set<PsiClass> out,
                                        Set<String> visited, GlobalSearchScope scope) {
        if (type == null) return;

        PsiClass resolved = PsiUtil.resolveClassInType(type);
        if (resolved == null || resolved.isInterface() || resolved.isEnum() || resolved.isAnnotationType()) return;

        String fqn = resolved.getQualifiedName();
        if (fqn == null || visited.contains(fqn)) return;

        // 🚫 Skip core Java/known-library classes
        if (fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("jakarta.") || fqn.startsWith("org.jetbrains.")) return;

        visited.add(fqn);
        out.add(resolved);

        // Recursively collect fields from custom POJOs only
        for (PsiField field : resolved.getAllFields()) {
            collectFromType(project, field.getType(), out, visited, scope);
        }
    }

}
