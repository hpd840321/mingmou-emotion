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

        public BBox getBbox() { return bbox; }
        public void setBbox(BBox bbox) { this.bbox = bbox; }
        public String getFaceId() { return faceId; }
        public void setFaceId(String faceId) { this.faceId = faceId; }
        public Float getConfidence() { return confidence; }
        public void setConfidence(Float confidence) { this.confidence = confidence; }
    }

    public static class BBox {
        private int x, y, width, height;
        public int getX() { return x; }
        public void setX(int x) { this.x = x; }
        public int getY() { return y; }
        public void setY(int y) { this.y = y; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
    }

    @SuppressWarnings("unchecked")
    public static FaceDetectionResult fromVmResponse(Map<String, Object> vmData) {
        FaceDetectionResult result = new FaceDetectionResult();
        List<Map<String, Object>> facesData = (List<Map<String, Object>>) vmData.get("faces");
        if (facesData != null) {
            result.setFaces(facesData.stream().map(f -> {
                Face face = new Face();
                List<Integer> bboxList = (List<Integer>) f.get("bbox");
                if (bboxList != null && bboxList.size() == 4) {
                    BBox bbox = new BBox();
                    bbox.setX(bboxList.get(0));
                    bbox.setY(bboxList.get(1));
                    bbox.setWidth(bboxList.get(2));
                    bbox.setHeight(bboxList.get(3));
                    face.setBbox(bbox);
                }
                if (f.get("confidence") != null) {
                    face.setConfidence(((Number) f.get("confidence")).floatValue());
                }
                return face;
            }).toList());
        }
        return result;
    }
}
