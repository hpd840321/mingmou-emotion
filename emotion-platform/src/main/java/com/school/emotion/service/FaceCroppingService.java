package com.school.emotion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class FaceCroppingService {

    private static final Logger log = LoggerFactory.getLogger(FaceCroppingService.class);

    private final Path croppedRoot;
    private final float marginRatio;

    public FaceCroppingService(
            @Value("${app.image.cropped-dir:./images/cropped}") String croppedDir,
            @Value("${app.face.crop-margin:0.3}") float marginRatio) {
        this.croppedRoot = Path.of(croppedDir);
        this.marginRatio = marginRatio;
    }

    public CropResult cropFace(Path originalImage, int x, int y, int w, int h,
                               String school, String className, String date, String period, long faceRecordId)
            throws IOException {
        BufferedImage img = ImageIO.read(originalImage.toFile());
        if (img == null) {
            return new CropResult(false, "Cannot read image: " + originalImage);
        }

        int mx = Math.max(1, (int) (w * marginRatio));
        int my = Math.max(1, (int) (h * marginRatio));
        int x1 = Math.max(0, x - mx);
        int y1 = Math.max(0, y - my);
        int x2 = Math.min(img.getWidth() - 1, x + w + mx);
        int y2 = Math.min(img.getHeight() - 1, y + h + my);

        if (x2 <= x1 || y2 <= y1) {
            return new CropResult(false, "Empty crop region");
        }

        BufferedImage crop = img.getSubimage(x1, y1, x2 - x1, y2 - y1);

        Path dir = croppedRoot.resolve(school).resolve(className)
                .resolve(date).resolve(sanitize(period));
        Files.createDirectories(dir);
        Path output = dir.resolve("face_" + faceRecordId + ".jpg");

        ImageIO.write(crop, "JPEG", output.toFile());

        return new CropResult(true, output.toAbsolutePath().toString());
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public record CropResult(boolean success, String path) {}
}
