package org.example.parser;

import java.util.List;

public record FunctionSignature(
        String name,
        String returnType,
        List<String> paramTypes
) {
}