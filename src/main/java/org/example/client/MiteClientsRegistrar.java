package org.example.client;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.support.*;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.Map;
import java.util.Set;

public class MiteClientsRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(
                EnableMiteClients.class.getName()
        );

        String[] basePackages;
        if (attrs != null && ((String[]) attrs.get("basePackages")).length > 0) {
            basePackages = (String[]) attrs.get("basePackages");
        } else {
            basePackages = new String[]{
                    metadata.getClassName().substring(0, metadata.getClassName().lastIndexOf('.'))
            };
        }

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(MiteClient.class));

        for (String basePackage : basePackages) {
            Set<org.springframework.beans.factory.config.BeanDefinition> candidates =
                    scanner.findCandidateComponents(basePackage);

            for (var candidate : candidates) {
                try {
                    Class<?> interfaceClass = Class.forName(candidate.getBeanClassName());
                    MiteClient annotation = interfaceClass.getAnnotation(MiteClient.class);

                    BeanDefinitionBuilder builder = BeanDefinitionBuilder
                            .genericBeanDefinition(MiteClientFactoryBean.class);
                    builder.addPropertyValue("interfaceClass", interfaceClass);
                    builder.addPropertyValue("script", annotation.script());

                    AbstractBeanDefinition beanDef = builder.getBeanDefinition();
                    beanDef.setAttribute("factoryBeanObjectType", interfaceClass);

                    String beanName = decapitalize(interfaceClass.getSimpleName());
                    registry.registerBeanDefinition(beanName, beanDef);

                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Failed to load MiteClient: " + candidate.getBeanClassName(), e);
                }
            }
        }
    }

    private String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}