package com.school.emotion.model.dto;

import java.util.List;
import java.util.Map;

public class FaceDetectionResult {
    private List<Face> faces;

    public List<Face> getFaces() { return faces; }
    public void setFaces(List<Face> faces) { this.faces = faces; }

    public static class Face {
        private BBox bbox;
        private String faceId;
        private Float confidence;
        private Float quality;

        public BBox getBbox() { return bbox; }
        public void setBbox(BBox bbox) { this.bbox = bbox; }
        public String getFaceId() { return faceId; }
        public void setFaceId(String faceId) { this.faceId = faceId; }
        public Float getConfidence() { return confidence; }
        public void setConfidence(Float confidence) { this.confidence = confidence; }
        public Float getQuality() { return quality; }
        public void setQuality(Float quality) { this.quality = quality; }
    }

    public static class BBox {
        private float x, y, width, height;
        public float getX() { return x; }
        public void setX(float x) { this.x = x; }
        public float getY() { return y; }
        public void setY(float y) { this.y = y; }
        public float getWidth() { return width; }
        public void setWidth(float width) { this.width = width; }
        public float getHeight() { return height; }
        public void setHeight(float height) { this.height = height; }
    }

    @SuppressWarnings("unchecked")
    public static FaceDetectionResult fromVmResponse(Map<String, Object> vmData) {
        FaceDetectionResult result = new FaceDetectionResult();
        List<Map<String, Object>> facesData = (List<Map<String, Object>>) vmData.get("faces");
        if (facesData != null) {
            result.setFaces(facesData.stream().map(f -> {
                Face face = new Face();
                @SuppressWarnings("unchecked")
                List<Number> bboxList = (List<Number>) f.get("bbox");
                if (bboxList != null && bboxList.size() == 4) {
                    BBox bbox = new BBox();
                    bbox.setX(bboxList.get(0).floatValue());
                    bbox.setY(bboxList.get(1).floatValue());
                    bbox.setWidth(bboxList.get(2).floatValue());
                    bbox.setHeight(bboxList.get(3).floatValue());
                    face.setBbox(bbox);
                }
                if (f.get("confidence") != null) {
                    face.setConfidence(((Number) f.get("confidence")).floatValue());
                }
                if (f.get("quality") != null) {
                    face.setQuality(((Number) f.get("quality")).floatValue());
                }
                return face;
            }).toList());
        }
        return result;
    }
}
