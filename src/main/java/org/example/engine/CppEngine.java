package org.example.engine;

public interface CppEngine {
    Object execute(String sourcePathOrCode, Object... args);
}