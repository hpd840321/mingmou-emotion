package com.school.emotion.service.ai;

import com.craftlabs.visionmind.core.grpc.proto.*;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class GrpcFaceServiceClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GrpcFaceServiceClient.class);

    private final ManagedChannel channel;
    private final FaceServiceGrpc.FaceServiceBlockingStub stub;

    // Feature flags matching C++ HF_ENABLE_* constants
    public static final long FEAT_DETECT     = 0x01;
    public static final long FEAT_RECOGNITION = 0x02;
    public static final long FEAT_LIVENESS   = 0x04;
    public static final long FEAT_MASK       = 0x08;
    public static final long FEAT_ATTRIBUTE  = 0x10;
    public static final long FEAT_QUALITY    = 0x20;
    public static final long FEAT_POSE       = 0x40;
    public static final long FEAT_EMOTION    = 0x80;

    // Standard features (detect + recognition + quality + emotion)
    // Intentionally excludes 0x04 (liveness), 0x08 (mask), 0x10 (attribute/gender/age), 0x40 (pose)
    // as those are not required by the business
    public static final long STANDARD_FEATURES = 0x01 | 0x02 | 0x20 | 0x80;

    private final String faceHost;
    private final int facePort;

    public GrpcFaceServiceClient(
            @Value("${app.face.grpc.host:face-1}") String faceHost,
            @Value("${app.face.grpc.port:50053}") int facePort) {
        this.faceHost = faceHost;
        this.facePort = facePort;
        log.info("Connecting to face gRPC service at {}:{}", faceHost, facePort);
        this.channel = Grpc.newChannelBuilderForAddress(
                faceHost, facePort, InsecureChannelCredentials.create())
                .maxInboundMessageSize(50 * 1024 * 1024)  // 50MB for large images
                .build();
        this.stub = FaceServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Full face analysis: detect + attributes + emotion + quality + features.
     * Returns all detected faces with all available attributes.
     */
    public FaceAnalysisResponse analyze(byte[] imageData, long enabledFeatures) {
        FaceAnalysisRequest request = FaceAnalysisRequest.newBuilder()
                .setImageData(com.google.protobuf.ByteString.copyFrom(imageData))
                .setEnabledFeatures(enabledFeatures)
                .build();

        return stub.withDeadlineAfter(180, TimeUnit.SECONDS).analyze(request);
    }

    /**
     * Single face detection (highest confidence face only).
     * Convenience method for the standard pipeline.
     */
    public FaceResult detectBestFace(byte[] imageData) {
        FaceAnalysisResponse response = analyze(imageData, FEAT_DETECT | FEAT_QUALITY | FEAT_ATTRIBUTE | FEAT_EMOTION);
        if (!response.getSuccess() || response.getFacesCount() == 0) {
            return null;
        }
        // Return highest confidence face
        FaceResult best = null;
        for (FaceResult face : response.getFacesList()) {
            if (best == null || face.getToken().getConfidence() > best.getToken().getConfidence()) {
                best = face;
            }
        }
        return best;
    }

    /**
     * Search for similar faces in the face library (Qdrant).
     */
    public FaceSearchResponse searchFaces(String imageUrl, String libraryId, int topK, float threshold) {
        FaceSearchRequest request = FaceSearchRequest.newBuilder()
                .setImageUrl(imageUrl)
                .setLibraryId(libraryId)
                .setTopK(topK)
                .setThreshold(threshold)
                .build();
        return stub.withDeadlineAfter(30, TimeUnit.SECONDS).searchFaces(request);
    }

    /**
     * 1:1 face comparison.
     */
    public FaceCompareResponse compareFaces(String imageA, String imageB) {
        FaceCompareRequest request = FaceCompareRequest.newBuilder()
                .setImageA(imageA)
                .setImageB(imageB)
                .build();
        return stub.withDeadlineAfter(30, TimeUnit.SECONDS).compareFaces(request);
    }

    @Override
    public void destroy() {
        log.info("Shutting down gRPC channel to {}:{}", faceHost, facePort);
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
