package org.example.config;

import org.example.compiler.CppCompiler;
import org.example.engine.CppEngine;
import org.example.engine.DefaultCppEngine;
import org.example.scanner.FunctionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import jakarta.annotation.PostConstruct;

@AutoConfiguration
@EnableConfigurationProperties(MiteProperties.class)
public class MiteAutoConfiguration {

    private final MiteProperties props;

    public MiteAutoConfiguration(MiteProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void initGlobalContext() {
        MiteContext.init(props.getAlignmentBytes());
        System.out.println("[MITE STARTUP] Blobal memory alignment set to: " + props.getAlignmentBytes() + " bytes.");
    }

    @Bean
    @ConditionalOnMissingBean
    public CppCompiler cppCompiler() {
        return new CppCompiler(
                props.getCacheDir(),
                props.getCompilerPath(),
                props.getCompilerFlags()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public FunctionRegistry functionRegistry() {
        return new FunctionRegistry(props.getScriptsDir());
    }

    @Bean
    @ConditionalOnMissingBean
    public CppEngine cppEngine(CppCompiler compiler, FunctionRegistry registry) {
        return new DefaultCppEngine(compiler, registry);
    }
}