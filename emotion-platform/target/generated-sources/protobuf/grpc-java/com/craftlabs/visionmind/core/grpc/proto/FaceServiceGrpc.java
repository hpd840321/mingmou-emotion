package com.craftlabs.visionmind.core.grpc.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: inference.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class FaceServiceGrpc {

  private FaceServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "visionmind.inference.v1.FaceService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.InferenceRequest,
      com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> getPredictMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Predict",
      requestType = com.craftlabs.visionmind.core.grpc.proto.InferenceRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.InferenceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.InferenceRequest,
      com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> getPredictMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.InferenceRequest, com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> getPredictMethod;
    if ((getPredictMethod = FaceServiceGrpc.getPredictMethod) == null) {
      synchronized (FaceServiceGrpc.class) {
        if ((getPredictMethod = FaceServiceGrpc.getPredictMethod) == null) {
          FaceServiceGrpc.getPredictMethod = getPredictMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.InferenceRequest, com.craftlabs.visionmind.core.grpc.proto.InferenceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Predict"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.InferenceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.InferenceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new FaceServiceMethodDescriptorSupplier("Predict"))
              .build();
        }
      }
    }
    return getPredictMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest,
      com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse> getAnalyzeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Analyze",
      requestType = com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest,
      com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse> getAnalyzeMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest, com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse> getAnalyzeMethod;
    if ((getAnalyzeMethod = FaceServiceGrpc.getAnalyzeMethod) == null) {
      synchronized (FaceServiceGrpc.class) {
        if ((getAnalyzeMethod = FaceServiceGrpc.getAnalyzeMethod) == null) {
          FaceServiceGrpc.getAnalyzeMethod = getAnalyzeMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest, com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Analyze"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse.getDefaultInstance()))
              .setSchemaDescriptor(new FaceServiceMethodDescriptorSupplier("Analyze"))
              .build();
        }
      }
    }
    return getAnalyzeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest,
      com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse> getTileDetectMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TileDetect",
      requestType = com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest,
      com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse> getTileDetectMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest, com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse> getTileDetectMethod;
    if ((getTileDetectMethod = FaceServiceGrpc.getTileDetectMethod) == null) {
      synchronized (FaceServiceGrpc.class) {
        if ((getTileDetectMethod = FaceServiceGrpc.getTileDetectMethod) == null) {
          FaceServiceGrpc.getTileDetectMethod = getTileDetectMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest, com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TileDetect"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse.getDefaultInstance()))
              .setSchemaDescriptor(new FaceServiceMethodDescriptorSupplier("TileDetect"))
              .build();
        }
      }
    }
    return getTileDetectMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest,
      com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse> getCompareFacesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CompareFaces",
      requestType = com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest,
      com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse> getCompareFacesMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest, com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse> getCompareFacesMethod;
    if ((getCompareFacesMethod = FaceServiceGrpc.getCompareFacesMethod) == null) {
      synchronized (FaceServiceGrpc.class) {
        if ((getCompareFacesMethod = FaceServiceGrpc.getCompareFacesMethod) == null) {
          FaceServiceGrpc.getCompareFacesMethod = getCompareFacesMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest, com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CompareFaces"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse.getDefaultInstance()))
              .setSchemaDescriptor(new FaceServiceMethodDescriptorSupplier("CompareFaces"))
              .build();
        }
      }
    }
    return getCompareFacesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest,
      com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse> getSearchFacesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SearchFaces",
      requestType = com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest,
      com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse> getSearchFacesMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest, com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse> getSearchFacesMethod;
    if ((getSearchFacesMethod = FaceServiceGrpc.getSearchFacesMethod) == null) {
      synchronized (FaceServiceGrpc.class) {
        if ((getSearchFacesMethod = FaceServiceGrpc.getSearchFacesMethod) == null) {
          FaceServiceGrpc.getSearchFacesMethod = getSearchFacesMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest, com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SearchFaces"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new FaceServiceMethodDescriptorSupplier("SearchFaces"))
              .build();
        }
      }
    }
    return getSearchFacesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest,
      com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse> getGetGpuMetricsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetGpuMetrics",
      requestType = com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest,
      com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse> getGetGpuMetricsMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest, com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse> getGetGpuMetricsMethod;
    if ((getGetGpuMetricsMethod = FaceServiceGrpc.getGetGpuMetricsMethod) == null) {
      synchronized (FaceServiceGrpc.class) {
        if ((getGetGpuMetricsMethod = FaceServiceGrpc.getGetGpuMetricsMethod) == null) {
          FaceServiceGrpc.getGetGpuMetricsMethod = getGetGpuMetricsMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest, com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetGpuMetrics"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new FaceServiceMethodDescriptorSupplier("GetGpuMetrics"))
              .build();
        }
      }
    }
    return getGetGpuMetricsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static FaceServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<FaceServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<FaceServiceStub>() {
        @java.lang.Override
        public FaceServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new FaceServiceStub(channel, callOptions);
        }
      };
    return FaceServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static FaceServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<FaceServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<FaceServiceBlockingStub>() {
        @java.lang.Override
        public FaceServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new FaceServiceBlockingStub(channel, callOptions);
        }
      };
    return FaceServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static FaceServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<FaceServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<FaceServiceFutureStub>() {
        @java.lang.Override
        public FaceServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new FaceServiceFutureStub(channel, callOptions);
        }
      };
    return FaceServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void predict(com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPredictMethod(), responseObserver);
    }

    /**
     */
    default void analyze(com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAnalyzeMethod(), responseObserver);
    }

    /**
     */
    default void tileDetect(com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTileDetectMethod(), responseObserver);
    }

    /**
     */
    default void compareFaces(com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCompareFacesMethod(), responseObserver);
    }

    /**
     */
    default void searchFaces(com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSearchFacesMethod(), responseObserver);
    }

    /**
     */
    default void getGpuMetrics(com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetGpuMetricsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service FaceService.
   */
  public static abstract class FaceServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return FaceServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service FaceService.
   */
  public static final class FaceServiceStub
      extends io.grpc.stub.AbstractAsyncStub<FaceServiceStub> {
    private FaceServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected FaceServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new FaceServiceStub(channel, callOptions);
    }

    /**
     */
    public void predict(com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPredictMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void analyze(com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAnalyzeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void tileDetect(com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTileDetectMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void compareFaces(com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCompareFacesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void searchFaces(com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSearchFacesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getGpuMetrics(com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetGpuMetricsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service FaceService.
   */
  public static final class FaceServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<FaceServiceBlockingStub> {
    private FaceServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected FaceServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new FaceServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.InferenceResponse predict(com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPredictMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse analyze(com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAnalyzeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse tileDetect(com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTileDetectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse compareFaces(com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCompareFacesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse searchFaces(com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSearchFacesMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse getGpuMetrics(com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetGpuMetricsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service FaceService.
   */
  public static final class FaceServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<FaceServiceFutureStub> {
    private FaceServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected FaceServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new FaceServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> predict(
        com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPredictMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse> analyze(
        com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAnalyzeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse> tileDetect(
        com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTileDetectMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse> compareFaces(
        com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCompareFacesMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse> searchFaces(
        com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSearchFacesMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse> getGpuMetrics(
        com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetGpuMetricsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PREDICT = 0;
  private static final int METHODID_ANALYZE = 1;
  private static final int METHODID_TILE_DETECT = 2;
  private static final int METHODID_COMPARE_FACES = 3;
  private static final int METHODID_SEARCH_FACES = 4;
  private static final int METHODID_GET_GPU_METRICS = 5;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_PREDICT:
          serviceImpl.predict((com.craftlabs.visionmind.core.grpc.proto.InferenceRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.InferenceResponse>) responseObserver);
          break;
        case METHODID_ANALYZE:
          serviceImpl.analyze((com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse>) responseObserver);
          break;
        case METHODID_TILE_DETECT:
          serviceImpl.tileDetect((com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse>) responseObserver);
          break;
        case METHODID_COMPARE_FACES:
          serviceImpl.compareFaces((com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse>) responseObserver);
          break;
        case METHODID_SEARCH_FACES:
          serviceImpl.searchFaces((com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse>) responseObserver);
          break;
        case METHODID_GET_GPU_METRICS:
          serviceImpl.getGpuMetrics((com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getPredictMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.craftlabs.visionmind.core.grpc.proto.InferenceRequest,
              com.craftlabs.visionmind.core.grpc.proto.InferenceResponse>(
                service, METHODID_PREDICT)))
        .addMethod(
          getAnalyzeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisRequest,
              com.craftlabs.visionmind.core.grpc.proto.FaceAnalysisResponse>(
                service, METHODID_ANALYZE)))
        .addMethod(
          getTileDetectMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.craftlabs.visionmind.core.grpc.proto.TileDetectRequest,
              com.craftlabs.visionmind.core.grpc.proto.TileDetectResponse>(
                service, METHODID_TILE_DETECT)))
        .addMethod(
          getCompareFacesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.craftlabs.visionmind.core.grpc.proto.FaceCompareRequest,
              com.craftlabs.visionmind.core.grpc.proto.FaceCompareResponse>(
                service, METHODID_COMPARE_FACES)))
        .addMethod(
          getSearchFacesMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.craftlabs.visionmind.core.grpc.proto.FaceSearchRequest,
              com.craftlabs.visionmind.core.grpc.proto.FaceSearchResponse>(
                service, METHODID_SEARCH_FACES)))
        .addMethod(
          getGetGpuMetricsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.craftlabs.visionmind.core.grpc.proto.GpuMetricsRequest,
              com.craftlabs.visionmind.core.grpc.proto.GpuMetricsResponse>(
                service, METHODID_GET_GPU_METRICS)))
        .build();
  }

  private static abstract class FaceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    FaceServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.craftlabs.visionmind.core.grpc.proto.InferenceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("FaceService");
    }
  }

  private static final class FaceServiceFileDescriptorSupplier
      extends FaceServiceBaseDescriptorSupplier {
    FaceServiceFileDescriptorSupplier() {}
  }

  private static final class FaceServiceMethodDescriptorSupplier
      extends FaceServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    FaceServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (FaceServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new FaceServiceFileDescriptorSupplier())
              .addMethod(getPredictMethod())
              .addMethod(getAnalyzeMethod())
              .addMethod(getTileDetectMethod())
              .addMethod(getCompareFacesMethod())
              .addMethod(getSearchFacesMethod())
              .addMethod(getGetGpuMetricsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
