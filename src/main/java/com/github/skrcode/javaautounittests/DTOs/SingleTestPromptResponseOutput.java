package com.github.skrcode.javaautounittests.DTOs;

import java.util.List;

public class SingleTestPromptResponseOutput {
    private List<String> testClassCodeForEachIndividualClass;
    private List<List<String>> contextClassesForEachIndividualClass;

    public List<String> getTestClassCodeForEachIndividualClass() {
        return testClassCodeForEachIndividualClass;
    }

    public void setTestClassCodeForEachIndividualClass(List<String> testClassCodeForEachIndividualClass) {
        this.testClassCodeForEachIndividualClass = testClassCodeForEachIndividualClass;
    }

    public List<List<String>> getContextClassesForEachIndividualClass() {
        return contextClassesForEachIndividualClass;
    }

    public void setContextClassesForEachIndividualClass(List<List<String>> contextClassesForEachIndividualClass) {
        this.contextClassesForEachIndividualClass = contextClassesForEachIndividualClass;
    }


}
