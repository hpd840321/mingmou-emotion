package com.craftlabs.visionmind.core.grpc.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: inference.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class EmotionServiceGrpc {

  private EmotionServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "visionmind.inference.v1.EmotionService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.EmotionRequest,
      com.craftlabs.visionmind.core.grpc.proto.EmotionResponse> getPredictMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Predict",
      requestType = com.craftlabs.visionmind.core.grpc.proto.EmotionRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.EmotionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.EmotionRequest,
      com.craftlabs.visionmind.core.grpc.proto.EmotionResponse> getPredictMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.EmotionRequest, com.craftlabs.visionmind.core.grpc.proto.EmotionResponse> getPredictMethod;
    if ((getPredictMethod = EmotionServiceGrpc.getPredictMethod) == null) {
      synchronized (EmotionServiceGrpc.class) {
        if ((getPredictMethod = EmotionServiceGrpc.getPredictMethod) == null) {
          EmotionServiceGrpc.getPredictMethod = getPredictMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.EmotionRequest, com.craftlabs.visionmind.core.grpc.proto.EmotionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Predict"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.EmotionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.EmotionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EmotionServiceMethodDescriptorSupplier("Predict"))
              .build();
        }
      }
    }
    return getPredictMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EmotionServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EmotionServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EmotionServiceStub>() {
        @java.lang.Override
        public EmotionServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EmotionServiceStub(channel, callOptions);
        }
      };
    return EmotionServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EmotionServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EmotionServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EmotionServiceBlockingStub>() {
        @java.lang.Override
        public EmotionServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EmotionServiceBlockingStub(channel, callOptions);
        }
      };
    return EmotionServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EmotionServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EmotionServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EmotionServiceFutureStub>() {
        @java.lang.Override
        public EmotionServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EmotionServiceFutureStub(channel, callOptions);
        }
      };
    return EmotionServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void predict(com.craftlabs.visionmind.core.grpc.proto.EmotionRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.EmotionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPredictMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service EmotionService.
   */
  public static abstract class EmotionServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return EmotionServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service EmotionService.
   */
  public static final class EmotionServiceStub
      extends io.grpc.stub.AbstractAsyncStub<EmotionServiceStub> {
    private EmotionServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EmotionServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EmotionServiceStub(channel, callOptions);
    }

    /**
     */
    public void predict(com.craftlabs.visionmind.core.grpc.proto.EmotionRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.EmotionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPredictMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service EmotionService.
   */
  public static final class EmotionServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<EmotionServiceBlockingStub> {
    private EmotionServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EmotionServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EmotionServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.EmotionResponse predict(com.craftlabs.visionmind.core.grpc.proto.EmotionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPredictMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service EmotionService.
   */
  public static final class EmotionServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<EmotionServiceFutureStub> {
    private EmotionServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EmotionServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EmotionServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.EmotionResponse> predict(
        com.craftlabs.visionmind.core.grpc.proto.EmotionRequest request) {
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
          serviceImpl.predict((com.craftlabs.visionmind.core.grpc.proto.EmotionRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.EmotionResponse>) responseObserver);
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
              com.craftlabs.visionmind.core.grpc.proto.EmotionRequest,
              com.craftlabs.visionmind.core.grpc.proto.EmotionResponse>(
                service, METHODID_PREDICT)))
        .build();
  }

  private static abstract class EmotionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EmotionServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.craftlabs.visionmind.core.grpc.proto.InferenceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EmotionService");
    }
  }

  private static final class EmotionServiceFileDescriptorSupplier
      extends EmotionServiceBaseDescriptorSupplier {
    EmotionServiceFileDescriptorSupplier() {}
  }

  private static final class EmotionServiceMethodDescriptorSupplier
      extends EmotionServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    EmotionServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (EmotionServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EmotionServiceFileDescriptorSupplier())
              .addMethod(getPredictMethod())
              .build();
        }
      }
    }
    return result;
  }
}
