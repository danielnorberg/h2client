package io.norberg.h2client;

import com.spotify.netty4.util.BatchFlusher;
import com.twitter.hpack.Encoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ByteString;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.concurrent.GlobalEventExecutor;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameTypes.DATA;
import static io.netty.handler.codec.http2.Http2FrameTypes.HEADERS;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.netty.handler.logging.LogLevel.TRACE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;
import static io.norberg.h2client.Util.completableFuture;
import static io.norberg.h2client.Util.failure;

public class Http2Server {

  private static final Logger log = LoggerFactory.getLogger(Http2Server.class);

  private static final int MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

  private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);

  private final ChannelFuture bindFuture;
  private final Channel channel;
  private final RequestHandler requestHandler;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

  public Http2Server(final RequestHandler requestHandler) {
    this(requestHandler, 0);
  }

  public Http2Server(final RequestHandler requestHandler, final int port) {
    this(requestHandler, "", port);
  }

  public Http2Server(final RequestHandler requestHandler, final String hostname, final int port) {
    this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");

    final SslContext sslCtx = Util.defaultServerSslContext();
    final NioEventLoopGroup group = Util.defaultEventLoopGroup();

    final ServerBootstrap b = new ServerBootstrap()
        .option(ChannelOption.SO_BACKLOG, 1024)
        .group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new Initializer(sslCtx));

    this.bindFuture = b.bind(hostname, port);
    this.channel = bindFuture.channel();

    channels.add(channel);
  }

  public CompletableFuture<Void> close() {
    return completableFuture(channels.close())
        .thenRun(() -> closeFuture.complete(null));
  }

  public CompletableFuture<Void> closeFuture() {
    return closeFuture;
  }

  public String hostname() {
    final InetSocketAddress localAddress = (InetSocketAddress) channel.localAddress();
    if (localAddress == null) {
      return null;
    }
    return localAddress.getHostName();
  }

  public int port() {
    final InetSocketAddress localAddress = (InetSocketAddress) channel.localAddress();
    if (localAddress == null) {
      return -1;
    }
    return localAddress.getPort();
  }

  public ChannelFuture bindFuture() {
    return bindFuture;
  }


  private class Initializer extends ChannelInitializer<SocketChannel> {

    private final Http2FrameLogger logger = new Http2FrameLogger(TRACE, Initializer.class);

    private final SslContext sslCtx;

    public Initializer(final SslContext sslCtx) {
      this.sslCtx = sslCtx;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
      channels.add(ch);
      final Http2Settings settings = new Http2Settings();
      final Http2FrameReader reader = new Http2FrameReader(new DefaultHttp2HeadersDecoder(false));
      ch.pipeline().addLast(
          sslCtx.newHandler(ch.alloc()),
          new PrefaceHandler(settings),
          new ConnectionHandler(reader, settings, new FrameHandler(ch)),
          new ExceptionHandler());
    }
  }

  private class FrameHandler implements Http2FrameListener {

    private final IntObjectHashMap<Http2Request> requests = new IntObjectHashMap<>();
    private final Encoder headerEncoder = new Encoder(DEFAULT_HEADER_TABLE_SIZE);
    private final Channel channel;

    private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    private long maxConcurrentStreams;

    private BatchFlusher flusher;

    private Executor executor = new Executor() {
      @Override
      public void execute(final Runnable command) {
        if (channel.eventLoop().inEventLoop()) {
          command.run();
        } else {
          channel.eventLoop().execute(command);
        }
      }
    };

    private FrameHandler(final Channel channel) {
      this.channel = channel;
      this.flusher = new BatchFlusher(channel);
    }

    @Override
    public int onDataRead(final ChannelHandlerContext ctx, final int streamId, final ByteBuf data, final int padding,
                          final boolean endOfStream)
        throws Http2Exception {
      log.debug("got data: streamId={}, data={}, padding={}, endOfStream={}", streamId, data, padding, endOfStream);
      final Http2Request request = existingRequest(streamId);
      final int n = data.readableBytes();
      ByteBuf content = request.content();
      if (content == null) {
        content = ctx.alloc().buffer(data.readableBytes());
        request.content(content);
      }
      content.writeBytes(data);
      maybeDispatch(ctx, streamId, endOfStream, request);
      return n + padding;
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int padding, final boolean endOfStream) throws Http2Exception {
      log.debug("got headers: streamId={}, headers={}, padding={}, endOfStream={}",
                streamId, headers, padding, endOfStream);
      final Http2Request request = newOrExistingRequest(streamId);
      request.headers(headers);
      maybeDispatch(ctx, streamId, endOfStream, request);
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int streamDependency, final short weight, final boolean exclusive,
                              final int padding, final boolean endOfStream)
        throws Http2Exception {
      log.debug("got headers: streamId={}, headers={}, streamDependency={}, weight={}, exclusive={}, padding={}, "
                + "endOfStream={}", streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
      final Http2Request request = newOrExistingRequest(streamId);
      request.headers(headers);
      maybeDispatch(ctx, streamId, endOfStream, request);
    }

    @Override
    public void onPriorityRead(final ChannelHandlerContext ctx, final int streamId, final int streamDependency,
                               final short weight, final boolean exclusive) throws Http2Exception {
      log.debug("got priority: streamId={}, streamDependency={}, weight={}, exclusive={}",
                streamId, streamDependency, weight, exclusive);
    }

    @Override
    public void onRstStreamRead(final ChannelHandlerContext ctx, final int streamId, final long errorCode)
        throws Http2Exception {
      log.debug("got rst stream: streamId={}, errorCode={}", streamId, errorCode);
    }

    @Override
    public void onSettingsAckRead(final ChannelHandlerContext ctx) throws Http2Exception {
      log.debug("got settings ack");
    }

    @Override
    public void onSettingsRead(final ChannelHandlerContext ctx, final Http2Settings settings) throws Http2Exception {
      log.debug("got settings: {}", settings);
      if (settings.maxFrameSize() != null) {
        maxFrameSize = settings.maxFrameSize();
      }
      if (settings.maxConcurrentStreams() != null) {
        maxConcurrentStreams = settings.maxConcurrentStreams();
      }
    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {
      log.debug("got ping");
      // TODO: ack
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {
      log.debug("got ping ack");
    }

    @Override
    public void onPushPromiseRead(final ChannelHandlerContext ctx, final int streamId, final int promisedStreamId,
                                  final Http2Headers headers,
                                  final int padding) throws Http2Exception {
      log.debug("got push promise");
    }

    @Override
    public void onGoAwayRead(final ChannelHandlerContext ctx, final int lastStreamId, final long errorCode,
                             final ByteBuf debugData)
        throws Http2Exception {
      log.debug("got goaway, closing connection");
      ctx.close();
    }

    @Override
    public void onWindowUpdateRead(final ChannelHandlerContext ctx, final int streamId, final int windowSizeIncrement)
        throws Http2Exception {
      log.debug("got window update: streamId={}, windowSizeIncrement={}", streamId, windowSizeIncrement);
    }

    @Override
    public void onUnknownFrame(final ChannelHandlerContext ctx, final byte frameType, final int streamId,
                               final Http2Flags flags, final ByteBuf payload)
        throws Http2Exception {
      log.debug("got unknown frame: {} {} {} {}", frameType, streamId, flags, payload);
    }

    private Http2Request existingRequest(final int streamId) throws Http2Exception {
      final Http2Request request = requests.get(streamId);
      if (request == null) {
        throw Util.connectionError(PROTOCOL_ERROR, "Data Frame recieved for unknown stream id %d", streamId);
      }
      return request;
    }

    private Http2Request newOrExistingRequest(final int streamId) {
      Http2Request request = requests.get(streamId);
      if (request == null) {
        request = new Http2Request(streamId);
        requests.put(streamId, request);
      }
      return request;
    }

    private void maybeDispatch(final ChannelHandlerContext ctx, final int streamId, final boolean endOfStream,
                               final Http2Request request) {
      if (!endOfStream) {
        return;
      }

      // TODO: only add request if not only a single headers frame
      requests.remove(streamId);

      // Hand off request to request handler
      final CompletableFuture<Http2Response> responseFuture = dispatch(request);

      // Handle response
      responseFuture.whenCompleteAsync((response, ex) -> {
        // Return 500 for request handler errors
        if (ex != null) {
          response = new Http2Response(streamId, INTERNAL_SERVER_ERROR);
        }
        sendResponse(ctx, response);
      }, executor);
    }

    private void sendResponse(final ChannelHandlerContext ctx, final Http2Response response) {
      log.debug("sending response: {}", response);
      final boolean hasContent = response.hasContent();
      final ByteBuf buf = ctx.alloc().buffer();
      try {
        writeHeaders(buf, response.streamId(), response.headers(), !hasContent);
      } catch (IOException e) {
        ctx.fireExceptionCaught(e);
        return;
      }
      if (hasContent) {
        writeData(buf, response.streamId(), response.content(), true);
      }
      ctx.write(buf);
      flusher.flush();
    }

    private CompletableFuture<Http2Response> dispatch(final Http2Request request) {
      try {
        return requestHandler.handleRequest(request);
      } catch (Exception e) {
        return failure(e);
      }
    }

    private void writeHeaders(final ByteBuf buf, int streamId, Http2Headers headers, boolean endStream)
        throws IOException {
      final int headerIndex = buf.writerIndex();

      buf.ensureWritable(FRAME_HEADER_LENGTH);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      final int size = encodeHeaders(headers, buf);

      if (size > maxFrameSize) {
        // TODO: continuation frames
        throw new AssertionError();
      }

      final int flags = END_HEADERS | (endStream ? END_STREAM : 0);
      writeFrameHeader(buf, headerIndex, size, HEADERS, flags, streamId);

      // TODO: padding + fields
    }

    public void writeData(ByteBuf buf, int streamId, ByteBuf data, boolean endStream) {
      final int headerIndex = buf.writerIndex();
      int payloadLength = data.readableBytes();
      final int flags = endStream ? END_STREAM : 0;
      buf.ensureWritable(FRAME_HEADER_LENGTH);
      writeFrameHeader(buf, headerIndex, payloadLength, DATA, flags, streamId);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      // TODO: padding + fields
      buf.writeBytes(data);
    }

    private int encodeHeaders(Http2Headers headers, ByteBuf buffer) throws IOException {
      final ByteBufOutputStream stream = new ByteBufOutputStream(buffer);
      for (Map.Entry<ByteString, ByteString> header : headers) {
        headerEncoder.encodeHeader(stream, toBytes(header.getKey()), toBytes(header.getValue()), false);
      }
      return stream.writtenBytes();
    }

    private byte[] toBytes(ByteString s) {
      if (s.isEntireArrayUsed()) {
        return s.array();
      } else {
        return s.toByteArray();
      }
    }

  }

  private class ExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("caught exception, closing connection", cause);
      ctx.close();
    }
  }

  private class ConnectionHandler extends ByteToMessageDecoder {

    private final Http2FrameReader reader;
    private final Http2Settings settings;
    private final Http2FrameListener listener;

    public ConnectionHandler(final Http2FrameReader reader, final Http2Settings settings,
                             final Http2FrameListener listener) {
      this.reader = reader;
      this.settings = settings;
      this.listener = listener;
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out)
        throws Exception {
      reader.readFrames(ctx, in, listener);
    }
  }

  private static class PrefaceHandler extends ByteToMessageDecoder {

    private static final ByteString CLIENT_PREFACE =
        ByteString.fromAscii("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

    private int prefaceIndex;

    private final Http2Settings settings;

    private PrefaceHandler(final Http2Settings settings) {
      this.settings = settings;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      writeSettings(ctx);
      ctx.flush();
    }

    private void writeSettings(final ChannelHandlerContext ctx) {
      final ByteBuf buf = ctx.alloc().buffer(FRAME_HEADER_LENGTH);
      writeFrameHeader(buf, 0, 0, SETTINGS, 0, 0);
      buf.writerIndex(FRAME_HEADER_LENGTH);
      ctx.write(buf);
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out)
        throws Exception {
      final int prefaceRemaining = CLIENT_PREFACE.length() - prefaceIndex;
      assert prefaceRemaining > 0;
      final int n = Math.min(in.readableBytes(), prefaceRemaining);
      for (int i = 0; i < n; i++, prefaceIndex++) {
        if (in.readByte() != CLIENT_PREFACE.byteAt(prefaceIndex)) {
          throw new Http2Exception(PROTOCOL_ERROR, "bad preface");
        }
      }
      if (prefaceIndex == CLIENT_PREFACE.length()) {
        ctx.pipeline().remove(this);
      }
    }
  }
}