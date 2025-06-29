package com.github.skrcode.javaautounittests.DTOs;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public class ScenariosResponseOutput {

    @JsonPropertyDescription("Output Test Scenarios")
    public List<TestScenario> testScenarios;

    public static class TestScenario {
        @JsonPropertyDescription("Name of actual method that is to be tested")
        public String methodname;

        @JsonPropertyDescription("Return Type of actual method that is to be tested")
        public String returntype;

        @JsonPropertyDescription("Short description of the test Scenario")
        public String scenario;

        @Override
        public String toString() {
            return "TestScenario{" +
                    "methodname='" + methodname + '\'' +
                    ", returntype='" + returntype + '\'' +
                    ", scenario='" + scenario + '\'' +
                    '}';
        }

        public String toOneLiner() {
            return "TestScenario{" +
                    "methodname='" + methodname + '\'' +
                    ", returntype='" + returntype + '\'' +
                    '}';
        }
    }
}
