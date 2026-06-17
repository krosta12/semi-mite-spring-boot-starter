package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "mite")
public class MiteProperties {

    private Path cacheDir = Path.of(".mite-cache");
    private Path scriptsDir = Path.of("cppScripts");
    private String compilerPath = null;

    private List<String> compilerFlags = new ArrayList<>(List.of("-O2"));

    private int alignmentBytes = 4;

    public String getCompilerPath() {
        return compilerPath;
    }

    public void setCompilerPath(String compilerPath) {
        this.compilerPath = compilerPath;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public Path getScriptsDir() {
        return scriptsDir;
    }

    public void setScriptsDir(Path scriptsDir) {
        this.scriptsDir = scriptsDir;
    }

    public List<String> getCompilerFlags() {
        return compilerFlags;
    }

    public void setCompilerFlags(List<String> compilerFlags) {
        this.compilerFlags = compilerFlags;
    }

    public int getAlignmentBytes() {
        return alignmentBytes;
    }

    public void setAlignmentBytes(int alignmentBytes) {
        this.alignmentBytes = alignmentBytes;
    }
}