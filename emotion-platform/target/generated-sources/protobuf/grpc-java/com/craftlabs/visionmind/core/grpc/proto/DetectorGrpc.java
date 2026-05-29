package com.craftlabs.visionmind.core.grpc.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Unified inference service interface — all model services implement this
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: inference.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class DetectorGrpc {

  private DetectorGrpc() {}

  public static final java.lang.String SERVICE_NAME = "visionmind.inference.v1.Detector";

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
    if ((getPredictMethod = DetectorGrpc.getPredictMethod) == null) {
      synchronized (DetectorGrpc.class) {
        if ((getPredictMethod = DetectorGrpc.getPredictMethod) == null) {
          DetectorGrpc.getPredictMethod = getPredictMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.InferenceRequest, com.craftlabs.visionmind.core.grpc.proto.InferenceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Predict"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.InferenceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.InferenceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new DetectorMethodDescriptorSupplier("Predict"))
              .build();
        }
      }
    }
    return getPredictMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static DetectorStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DetectorStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DetectorStub>() {
        @java.lang.Override
        public DetectorStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DetectorStub(channel, callOptions);
        }
      };
    return DetectorStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static DetectorBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DetectorBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DetectorBlockingStub>() {
        @java.lang.Override
        public DetectorBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DetectorBlockingStub(channel, callOptions);
        }
      };
    return DetectorBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static DetectorFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DetectorFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DetectorFutureStub>() {
        @java.lang.Override
        public DetectorFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DetectorFutureStub(channel, callOptions);
        }
      };
    return DetectorFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Unified inference service interface — all model services implement this
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void predict(com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPredictMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Detector.
   * <pre>
   * Unified inference service interface — all model services implement this
   * </pre>
   */
  public static abstract class DetectorImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return DetectorGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Detector.
   * <pre>
   * Unified inference service interface — all model services implement this
   * </pre>
   */
  public static final class DetectorStub
      extends io.grpc.stub.AbstractAsyncStub<DetectorStub> {
    private DetectorStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DetectorStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DetectorStub(channel, callOptions);
    }

    /**
     */
    public void predict(com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPredictMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Detector.
   * <pre>
   * Unified inference service interface — all model services implement this
   * </pre>
   */
  public static final class DetectorBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<DetectorBlockingStub> {
    private DetectorBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DetectorBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DetectorBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.InferenceResponse predict(com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPredictMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Detector.
   * <pre>
   * Unified inference service interface — all model services implement this
   * </pre>
   */
  public static final class DetectorFutureStub
      extends io.grpc.stub.AbstractFutureStub<DetectorFutureStub> {
    private DetectorFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DetectorFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DetectorFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.InferenceResponse> predict(
        com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPredictMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PREDICT = 0;

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
        .build();
  }

  private static abstract class DetectorBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    DetectorBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.craftlabs.visionmind.core.grpc.proto.InferenceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Detector");
    }
  }

  private static final class DetectorFileDescriptorSupplier
      extends DetectorBaseDescriptorSupplier {
    DetectorFileDescriptorSupplier() {}
  }

  private static final class DetectorMethodDescriptorSupplier
      extends DetectorBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    DetectorMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (DetectorGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new DetectorFileDescriptorSupplier())
              .addMethod(getPredictMethod())
              .build();
        }
      }
    }
    return result;
  }
}
