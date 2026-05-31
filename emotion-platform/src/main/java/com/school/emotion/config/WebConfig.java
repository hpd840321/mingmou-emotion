package com.school.emotion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String imagesDir;

    public WebConfig(@Value("${app.image.cropped-dir:./images/cropped}") String croppedDir) {
        // croppedDir is relative to JVM cwd (project root). The real images dir is under emotion-platform/
        Path abs = Path.of(croppedDir).toAbsolutePath().normalize();
        // If the resolved path doesn't exist, try prepending emotion-platform/
        if (!abs.toFile().exists()) {
            abs = Path.of("emotion-platform", croppedDir).toAbsolutePath().normalize();
        }
        this.imagesDir = abs.getParent().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations(imagesDir);
    }
}
