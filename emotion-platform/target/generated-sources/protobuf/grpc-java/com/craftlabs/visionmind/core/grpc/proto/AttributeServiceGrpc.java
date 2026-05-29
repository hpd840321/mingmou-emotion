package com.craftlabs.visionmind.core.grpc.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: inference.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class AttributeServiceGrpc {

  private AttributeServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "visionmind.inference.v1.AttributeService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.AttributeRequest,
      com.craftlabs.visionmind.core.grpc.proto.AttributeResponse> getPredictMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Predict",
      requestType = com.craftlabs.visionmind.core.grpc.proto.AttributeRequest.class,
      responseType = com.craftlabs.visionmind.core.grpc.proto.AttributeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.AttributeRequest,
      com.craftlabs.visionmind.core.grpc.proto.AttributeResponse> getPredictMethod() {
    io.grpc.MethodDescriptor<com.craftlabs.visionmind.core.grpc.proto.AttributeRequest, com.craftlabs.visionmind.core.grpc.proto.AttributeResponse> getPredictMethod;
    if ((getPredictMethod = AttributeServiceGrpc.getPredictMethod) == null) {
      synchronized (AttributeServiceGrpc.class) {
        if ((getPredictMethod = AttributeServiceGrpc.getPredictMethod) == null) {
          AttributeServiceGrpc.getPredictMethod = getPredictMethod =
              io.grpc.MethodDescriptor.<com.craftlabs.visionmind.core.grpc.proto.AttributeRequest, com.craftlabs.visionmind.core.grpc.proto.AttributeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Predict"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.AttributeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.craftlabs.visionmind.core.grpc.proto.AttributeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new AttributeServiceMethodDescriptorSupplier("Predict"))
              .build();
        }
      }
    }
    return getPredictMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AttributeServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AttributeServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AttributeServiceStub>() {
        @java.lang.Override
        public AttributeServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AttributeServiceStub(channel, callOptions);
        }
      };
    return AttributeServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AttributeServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AttributeServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AttributeServiceBlockingStub>() {
        @java.lang.Override
        public AttributeServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AttributeServiceBlockingStub(channel, callOptions);
        }
      };
    return AttributeServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AttributeServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AttributeServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AttributeServiceFutureStub>() {
        @java.lang.Override
        public AttributeServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AttributeServiceFutureStub(channel, callOptions);
        }
      };
    return AttributeServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void predict(com.craftlabs.visionmind.core.grpc.proto.AttributeRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.AttributeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPredictMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service AttributeService.
   */
  public static abstract class AttributeServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return AttributeServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service AttributeService.
   */
  public static final class AttributeServiceStub
      extends io.grpc.stub.AbstractAsyncStub<AttributeServiceStub> {
    private AttributeServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AttributeServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AttributeServiceStub(channel, callOptions);
    }

    /**
     */
    public void predict(com.craftlabs.visionmind.core.grpc.proto.AttributeRequest request,
        io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.AttributeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPredictMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service AttributeService.
   */
  public static final class AttributeServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<AttributeServiceBlockingStub> {
    private AttributeServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AttributeServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AttributeServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.craftlabs.visionmind.core.grpc.proto.AttributeResponse predict(com.craftlabs.visionmind.core.grpc.proto.AttributeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPredictMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service AttributeService.
   */
  public static final class AttributeServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<AttributeServiceFutureStub> {
    private AttributeServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AttributeServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AttributeServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.craftlabs.visionmind.core.grpc.proto.AttributeResponse> predict(
        com.craftlabs.visionmind.core.grpc.proto.AttributeRequest request) {
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
          serviceImpl.predict((com.craftlabs.visionmind.core.grpc.proto.AttributeRequest) request,
              (io.grpc.stub.StreamObserver<com.craftlabs.visionmind.core.grpc.proto.AttributeResponse>) responseObserver);
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
              com.craftlabs.visionmind.core.grpc.proto.AttributeRequest,
              com.craftlabs.visionmind.core.grpc.proto.AttributeResponse>(
                service, METHODID_PREDICT)))
        .build();
  }

  private static abstract class AttributeServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AttributeServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.craftlabs.visionmind.core.grpc.proto.InferenceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("AttributeService");
    }
  }

  private static final class AttributeServiceFileDescriptorSupplier
      extends AttributeServiceBaseDescriptorSupplier {
    AttributeServiceFileDescriptorSupplier() {}
  }

  private static final class AttributeServiceMethodDescriptorSupplier
      extends AttributeServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    AttributeServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (AttributeServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AttributeServiceFileDescriptorSupplier())
              .addMethod(getPredictMethod())
              .build();
        }
      }
    }
    return result;
  }
}
