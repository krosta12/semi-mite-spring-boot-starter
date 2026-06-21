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

/**
 * Spring Boot autoconfiguration entry point for the semi-mite framework.
 *
 * <p>Activated automatically via Spring Boot's autoconfiguration mechanism
 * when the starter is on the classpath. Reads {@link MiteProperties} and
 * registers the three core infrastructure beans:
 *
 * <ul>
 *   <li>{@link CppCompiler} - compiles {@code .cpp} files into native shared libraries</li>
 *   <li>{@link FunctionRegistry} - scans {@code cppScripts} for {@code // @mite} functions
 *       and maintains the live index of callable native symbols</li>
 *   <li>{@link CppEngine} (backed by {@link DefaultCppEngine}) - the primary API used
 *       by application code to invoke native functions</li>
 * </ul>
 *
 * <p>All three beans are conditional on absence ({@code @ConditionalOnMissingBean}),
 * so any of them can be replaced by a custom implementation in the application context
 * without disabling the rest of the framework.
 *
 * <p>During {@link #initGlobalContext()}, the configured alignment value is pushed
 * into {@link MiteContext}, making it available as a process-wide static for use
 * during native memory layout calculations in the marshalling layer.
 *
 * @see MiteProperties
 * @see MiteContext
 * @see CppCompiler
 * @see FunctionRegistry
 * @see DefaultCppEngine
 */
@AutoConfiguration
@EnableConfigurationProperties(MiteProperties.class)
public class MiteAutoConfiguration {

    private final MiteProperties props;

    /**
     * Constructs the autoconfiguration with the bound property source.
     *
     * @param props the resolved {@code mite.*} configuration properties
     */
    public MiteAutoConfiguration(MiteProperties props) {
        this.props = props;
    }

    /**
     * Initializes the global semi-mite context after the bean is constructed.
     *
     * <p>Pushes the configured {@code mite.alignment-bytes} value into
     * {@link MiteContext} so that the marshalling layer can access it
     * without requiring a Spring dependency injection point in every class
     * that performs memory layout calculations.
     */
    @PostConstruct
    public void initGlobalContext() {
        MiteContext.init(props.getAlignmentBytes());
    }

    /**
     * Creates the {@link CppCompiler} bean responsible for compiling
     * {@code .cpp} source files into native shared libraries.
     *
     * <p>Skipped if a {@code CppCompiler} bean is already present in the context,
     * allowing the application to supply a custom implementation.
     *
     * @return a configured {@link CppCompiler} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public CppCompiler cppCompiler() {
        return new CppCompiler(
                props.getCacheDir(),
                props.getCompilerPath(),
                props.getCompilerFlags()
        );
    }

    /**
     * Creates the {@link FunctionRegistry} bean that scans the configured
     * {@code cppScripts} directory for {@code // @mite}-marked functions
     * and maintains the index of native symbols available for invocation.
     *
     * <p>Skipped if a {@code FunctionRegistry} bean is already present in the context.
     *
     * @return a {@link FunctionRegistry} watching {@code mite.scripts-dir}
     */
    @Bean
    @ConditionalOnMissingBean
    public FunctionRegistry functionRegistry() {
        return new FunctionRegistry(props.getScriptsDir());
    }

    /**
     * Creates the {@link CppEngine} bean that serves as the primary API
     * for invoking native C++ functions from Java code.
     *
     * <p>The default implementation is {@link DefaultCppEngine}, which
     * handles argument marshalling, Panama downcall linking, and return
     * value unmarshalling.
     *
     * <p>Skipped if a {@code CppEngine} bean is already present in the context,
     * allowing the application to supply a custom engine implementation.
     *
     * @param compiler the {@link CppCompiler} bean used to build native libraries
     * @param registry the {@link FunctionRegistry} bean used to resolve function signatures
     * @return a {@link DefaultCppEngine} wired to the compiler and registry
     */
    @Bean
    @ConditionalOnMissingBean
    public CppEngine cppEngine(CppCompiler compiler, FunctionRegistry registry) {
        return new DefaultCppEngine(compiler, registry, props.getScriptsDir());
    }
}