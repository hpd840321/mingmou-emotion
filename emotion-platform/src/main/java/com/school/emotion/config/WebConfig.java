package com.school.emotion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String primaryImagesDir;
    private final String fallbackImagesDir;

    public WebConfig(@Value("${app.image.cropped-dir:./images/cropped}") String croppedDir) {
        // Primary: resolve from JVM cwd (project root)
        Path abs = Path.of(croppedDir).toAbsolutePath().normalize();
        this.primaryImagesDir = abs.getParent().toUri().toString();

        // Fallback: emotion-platform/images/ for older cropped files
        Path fallback = Path.of("emotion-platform", croppedDir).toAbsolutePath().normalize();
        this.fallbackImagesDir = fallback.getParent().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations(primaryImagesDir, fallbackImagesDir);
    }
}
