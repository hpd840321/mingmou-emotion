package com.craftlabs.visionmind.core.grpc.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: inference.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class OcrServiceGrpc {

  private OcrServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "visionmind.inference.v1.OcrService";

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
    if ((getPredictMethod = OcrServiceGrpc.getPredictMethod) == null) {
      synchronized (OcrServiceGrpc.class) {
        if ((getPredictMethod = OcrServiceGrpc.getPredictMethod) == null) {
          OcrServiceGrpc.getPredictMethod = getPredictMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.InferenceRequest, com.craftlabs.visionmind.core.grpc.proto.InferenceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Predict"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.InferenceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.InferenceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new OcrServiceMethodDescriptorSupplier("Predict"))
              .build();
        }
      }
    }
    return getPredictMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OcrServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OcrServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OcrServiceStub>() {
        @java.lang.Override
        public OcrServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OcrServiceStub(channel, callOptions);
        }
      };
    return OcrServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OcrServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OcrServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OcrServiceBlockingStub>() {
        @java.lang.Override
        public OcrServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OcrServiceBlockingStub(channel, callOptions);
        }
      };
    return OcrServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OcrServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OcrServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OcrServiceFutureStub>() {
        @java.lang.Override
        public OcrServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OcrServiceFutureStub(channel, callOptions);
        }
      };
    return OcrServiceFutureStub.newStub(factory, channel);
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
  }

  /**
   * Base class for the server implementation of the service OcrService.
   */
  public static abstract class OcrServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OcrServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OcrService.
   */
  public static final class OcrServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OcrServiceStub> {
    private OcrServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OcrServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OcrServiceStub(channel, callOptions);
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
   * A stub to allow clients to do synchronous rpc calls to service OcrService.
   */
  public static final class OcrServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OcrServiceBlockingStub> {
    private OcrServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OcrServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OcrServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.InferenceResponse predict(com.craftlabs.visionmind.core.grpc.proto.InferenceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPredictMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OcrService.
   */
  public static final class OcrServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OcrServiceFutureStub> {
    private OcrServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OcrServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OcrServiceFutureStub(channel, callOptions);
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

  private static abstract class OcrServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OcrServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.craftlabs.visionmind.core.grpc.proto.InferenceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OcrService");
    }
  }

  private static final class OcrServiceFileDescriptorSupplier
      extends OcrServiceBaseDescriptorSupplier {
    OcrServiceFileDescriptorSupplier() {}
  }

  private static final class OcrServiceMethodDescriptorSupplier
      extends OcrServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OcrServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OcrServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OcrServiceFileDescriptorSupplier())
              .addMethod(getPredictMethod())
              .build();
        }
      }
    }
    return result;
  }
}
