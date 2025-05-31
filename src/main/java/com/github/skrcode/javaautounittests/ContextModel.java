package com.github.skrcode.javaautounittests;

import java.util.List;

public class ContextModel {
    public String qualifiedName;
    public List<String> methods;
    public List<DependencyCall> dependencies;
    public String existingTestSource;  // nullable – fed back when updating

    public static class DependencyCall {
        public String type;           // e.g. OwnerRepository
        public List<String> methods;  // e.g. [findById, save]
    }
}