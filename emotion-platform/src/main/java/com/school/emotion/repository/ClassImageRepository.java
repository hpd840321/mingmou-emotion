package com.school.emotion.repository;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.OffsetDateTime;
import java.util.List;

public interface ClassImageRepository extends JpaRepository<ClassImage, Long> {
    List<ClassImage> findByStatus(ImageStatus status);
    long countByStatus(ImageStatus status);
    boolean existsByImageUrl(String imageUrl);
    List<ClassImage> findByClazz_Id(Long classId);

    /**
     * Return parent directory path + status for all images, used to build
     * the directory tree status map without loading full entities.
     */
    @Query("SELECT ci.imageUrl, ci.status FROM ClassImage ci")
    List<Object[]> findImageUrlAndStatus();
}
