package com.school.emotion.model.dto;

public class ImageIngestResponse {
    private int code;
    private String message;
    private Data data;

    public ImageIngestResponse(int code, String message, Data data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static ImageIngestResponse accepted(Long imageId, int queuePosition) {
        return new ImageIngestResponse(0, "accepted", new Data(imageId, queuePosition));
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public Data getData() { return data; }

    public static class Data {
        private Long imageId;
        private int queuePosition;

        public Data(Long imageId, int queuePosition) {
            this.imageId = imageId;
            this.queuePosition = queuePosition;
        }

        public Long getImageId() { return imageId; }
        public int getQueuePosition() { return queuePosition; }
    }
}
