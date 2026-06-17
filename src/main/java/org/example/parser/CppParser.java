package org.example.parser;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class CppParser {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "int", "long", "double", "float", "bool", "std::string", "void", "const char*",
            "int8_t", "uint8_t", "int16_t", "uint16_t", "int32_t", "uint32_t", "int64_t", "uint64_t",
            "char", "unsigned char", "short", "unsigned short", "long long", "unsigned long long",

            //special types
            "int*", "long long*", "double*", "float*", "int32_t*", "int64_t*"
    );

    private static final Pattern SIGNATURE_PATTERN = Pattern.compile(
            "^\\s*(?:extern\\s+\"C\"\\s+)?(.+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{?"
    );

    public List<FunctionSignature> parse(Path cppFile) {
        try {
            List<String> lines = Files.readAllLines(cppFile);
            List<FunctionSignature> result = new ArrayList<>();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.equals("// @mite")) continue;

                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < lines.size()) {
                    String nextLine = lines.get(j).trim();
                    sb.append(" ").append(nextLine);
                    if (nextLine.contains("{") || nextLine.contains(";")) {
                        break;
                    }
                    if (nextLine.startsWith("//") || nextLine.startsWith("}")) {
                        break; 
                    }
                    j++;
                }

                String fullSignatureStr = sb.toString().trim();
                Optional<FunctionSignature> sig = parseSignature(fullSignatureStr);
                System.out.println("SIG for '" + fullSignatureStr + "': " + sig);
                sig.ifPresent(result::add);

                i = j - 1;
            }

            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + cppFile, e);
        }
    }

    private Optional<FunctionSignature> parseSignature(String line) {
        Matcher m = SIGNATURE_PATTERN.matcher(line);
        if (!m.find()) return Optional.empty();

        String returnType = m.group(1).trim();
        String name = m.group(2).trim();
        String paramsRaw = m.group(3).trim();

        if (!SUPPORTED_TYPES.contains(returnType) && (!returnType.endsWith("*") || returnType.equals("void*"))) {
            return Optional.empty();
        }

        List<String> paramTypes = parseParamTypes(paramsRaw);
        if (paramTypes == null) return Optional.empty();

        return Optional.of(new FunctionSignature(name, returnType, paramTypes));
    }

    private List<String> parseParamTypes(String paramsRaw) {
        if (paramsRaw.isBlank() || paramsRaw.trim().equals("void")) return List.of();

        List<String> types = new ArrayList<>();
        for (String param : paramsRaw.split(",")) {
            String trimmed = param.trim();
            String type = extractType(trimmed);

            if (type == null || (!SUPPORTED_TYPES.contains(type) && (!type.endsWith("*") || type.equals("void*")))) {
                return null;
            }
            types.add(type);
        }
        return types;
    }

    private String extractType(String param) {
        if (param.matches("const\\s+char\\s*\\*.*")) return "const char*";
        if (param.trim().equals("void")) return null;

        boolean isPointer = param.contains("*") && !param.contains("char");

        param = param.replaceAll("\\bconst\\b", "").replaceAll("&", "").trim().replaceAll("\\s+", " ");

        if (SUPPORTED_TYPES.contains(param)) {
            return param;
        }

        int lastSpace = param.lastIndexOf(' ');
        if (lastSpace != -1) {
            String typeCandidate = param.substring(0, lastSpace).trim();

            typeCandidate = typeCandidate.replaceAll("\\s+\\*", "*");

            if (isPointer && !typeCandidate.endsWith("*")) {
                typeCandidate += "*";
            }
            if (typeCandidate.endsWith("*") || SUPPORTED_TYPES.contains(typeCandidate)) {
                return typeCandidate;
            }
        }

        if (param.startsWith("std::string")) return "std::string";

        return null;
    }
}