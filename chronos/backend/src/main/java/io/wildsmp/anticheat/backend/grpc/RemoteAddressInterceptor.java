package io.wildsmp.anticheat.backend.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.net.SocketAddress;

/** Bridges the connecting peer's address into Context so handlers can read it without a ServerCall reference - no auth, see AnticheatServiceImpl.remoteIpOf. */
public final class RemoteAddressInterceptor implements ServerInterceptor {

    public static final Context.Key<SocketAddress> REMOTE_ADDR_KEY = Context.key("anticheat-remote-addr");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        SocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        Context context = Context.current().withValue(REMOTE_ADDR_KEY, remoteAddr);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
