package com.github.skrcode.javaautounittests.DTOs;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public class ResponseOutput {
    @JsonPropertyDescription("Output Test Class")
    public String outputTestClass;

    @JsonPropertyDescription("Output Required Classes Paths for Additional Context ")
    public List<String> outputRequiredClassContextPaths;
}
