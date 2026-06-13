package com.school.emotion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String imagesDir;
    private final String dataDir;

    public WebConfig(
            @Value("${app.image.cropped-dir:./images/cropped}") String croppedDir,
            @Value("${app.data.dir:../data}") String dataDir) {
        this.imagesDir = Path.of(croppedDir).toAbsolutePath().normalize().getParent().toUri().toString();
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations(imagesDir);
        registry.addResourceHandler("/data/**")
                .addResourceLocations(dataDir);
    }
}
