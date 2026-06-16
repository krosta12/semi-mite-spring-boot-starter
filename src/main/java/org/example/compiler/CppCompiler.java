package org.example.compiler;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CppCompiler {

    private final Path cacheDir;
    private final Map<String, CachedLib> cache = new ConcurrentHashMap<>();

    public CppCompiler(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new MiteException("Failed to create cache directory: " + cacheDir, e);
        }
    }

    public Path compile(Path cppFile) {
        String key = cppFile.toAbsolutePath().toString();
        long lastModified = lastModified(cppFile);

        CachedLib cached = cache.get(key);
        if (cached != null && cached.timestamp == lastModified) {
            return cached.lib;
        }

        Path lib = doCompile(cppFile);
        cache.put(key, new CachedLib(lib, lastModified));
        return lib;
    }

    public Path compileInline(String code) {
        String key = "inline:" + code.hashCode();
        CachedLib cached = cache.get(key);
        if (cached != null) return cached.lib;

        try {
            Path tmp = Files.createTempFile(cacheDir, "mite_inline_", ".cpp");
            Files.writeString(tmp, code);
            Path lib = doCompile(tmp);
            cache.put(key, new CachedLib(lib, -1));
            return lib;
        } catch (IOException e) {
            throw new MiteException("Inline code writing error", e);
        }
    }

    private Path doCompile(Path cppFile) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String name = cppFile.getFileName().toString().replace(".cpp", "") + "_" + System.nanoTime();
        String libName = win ? name + ".dll" : "lib" + name + ".so";
        Path out = cacheDir.resolve(libName);

        String compiler = findCompiler();

        List<String> cmd = new ArrayList<>();
        cmd.add(compiler);
        cmd.add("-shared");
        if (!win) cmd.add("-fPIC");
        cmd.add("-O2");
        if (win) cmd.add("-Wl,--kill-at");
        cmd.add("-o");
        cmd.add(out.toAbsolutePath().toString());
        cmd.add(cppFile.toAbsolutePath().toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (win) {
                pb.environment().put("PATH",
                        "C:\\msys64\\ucrt64\\bin;" + pb.environment().getOrDefault("PATH", ""));
            }
            pb.redirectErrorStream(false);
            Process p = pb.start();

            String stdout = new String(p.getInputStream().readAllBytes());
            String stderr = new String(p.getErrorStream().readAllBytes());
            int exitCode = p.waitFor();

            System.out.println("CMD: " + String.join(" ", cmd));
            System.out.println("stdout: " + stdout);
            System.out.println("stderr: " + stderr);
            System.out.println("Exit code: " + exitCode);

            if (exitCode != 0) throw new MiteException("Compilation error:\n" + stdout + "\n" + stderr);
            return out;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiteException("Failed to start compiler: " + compiler, e);
        }
    }

    private String findCompiler() {
        List<String> candidates = List.of(
                "g++",
                "clang++",
                "C:\\msys64\\ucrt64\\bin\\g++.exe",
                "C:\\msys64\\mingw64\\bin\\g++.exe"
        );

        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "--version")
                        .redirectErrorStream(true)
                        .start();
                p.waitFor();
                if (p.exitValue() == 0) return c;
            } catch (Exception ignored) {}
        }
        throw new MiteException(
                "C++ compiler does not found. Install g++ or clang++ and add to PATH."
        );
    }

    private long lastModified(Path f) {
        try { return Files.getLastModifiedTime(f).toMillis(); }
        catch (IOException e) { return -1; }
    }

    private record CachedLib(Path lib, long timestamp) {}
}