package com.github.skrcode.javaautounittests.DTOs;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public class ContextClassesResponseOutput {
    @JsonPropertyDescription("Output Required Classes Paths for Additional Context")
    public List<String> outputRequiredClassContextPaths;
}
