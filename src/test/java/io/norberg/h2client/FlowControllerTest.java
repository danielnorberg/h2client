package io.norberg.h2client;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2Exception;

import static io.norberg.h2client.FlowControllerTest.FlushOp.Flags.END_OF_STREAM;
import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.h2client.TestUtil.randomByteBuf;
import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.intThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class FlowControllerTest {

  interface Context {

  }

  private FlowController<Context, Stream> controller = new FlowController<>();

  @Mock Context ctx;
  @Mock StreamWriter<Context, Stream> writer;

  @Captor ArgumentCaptor<Integer> bufferSizeCaptor;

  @Test
  public void testRemoteConnectionWindowDefault() throws Exception {
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE));
  }

  @Test
  public void testRemoteInitialStreamWindowDefault() throws Exception {
    assertThat(controller.remoteInitialStreamWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE));
  }

  @Test
  public void testConnectionWindowUpdate() throws Exception {
    controller.remoteConnectionWindowUpdate(4711);
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE + 4711));
  }

  @Test
  public void testRemoteInitialStreamWindow() throws Exception {
    final int remoteInitialStreamWindow = controller.remoteInitialStreamWindow();

    final Stream stream = new Stream(17, randomByteBuf(4711));
    assertThat(stream.remoteWindow, is(0));

    controller.start(stream);

    // Verify that the stream remote window is applied when it is started
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow));
  }

  @Test
  public void testHappyPathSingleEmptyStream() throws Exception {
    final Stream stream = startStream(1, 0);
    verifyFlush(stream(stream).headers(END_OF_STREAM));
  }

  @Test
  public void testHappyPathSingleStream() throws Exception {
    final Stream stream = startStream(1, 17);
    verifyFlush(stream(stream).headers().estimate(17).write(17, END_OF_STREAM));
  }

  @Test
  public void testHappyPathSingleStreamMultipleFrames() throws Exception {
    controller.remoteMaxFrameSize(8);
    final Stream stream = startStream(1, 17);
    verifyFlush(stream(stream).headers()
                    .estimate(8).estimate(1)
                    .write(8, 2).write(1, END_OF_STREAM));
  }

  @Test
  public void testHappyPathTwoConcurrentStreams() throws Exception {
    final int size1 = 7;
    final int size2 = 17;
    final Stream stream1 = startStream(1, size1);
    final Stream stream2 = startStream(3, size2);
    verifyFlush(
        stream(stream1).headers().estimate(size1).write(size1, END_OF_STREAM),
        stream(stream2).headers().estimate(size2).write(size2, END_OF_STREAM));
  }

  @Test
  public void testHappyPathSequentialStreams() throws Exception {

    final int[] sizes = {17, 4711, 32, 45, 158};

    int expectedRemoteConnectionWindow = controller.remoteConnectionWindow();
    int id = 1;

    for (final int size : sizes) {
      final Stream stream = startStream(id, size);
      verifyFlush(stream(stream).headers().estimate(size).write(size, END_OF_STREAM));
      expectedRemoteConnectionWindow -= size;
      assertThat(controller.remoteConnectionWindow(), is(expectedRemoteConnectionWindow));
      id += 2;
    }
  }

  @Test
  public void testStreamWindowExhaustionSingleStream() throws Exception {

    controller.remoteInitialStreamWindowSizeUpdate(3);

    // Three frames:
    // Operation  | Data | Stream Window
    // ---------------------------------
    //            | 6    | 3
    // Write:  3  | 3    | 0
    // Update: 2  | 3    | 2
    // Write:  2  | 1    | 0
    // Update: 3  | 1    | 3
    // Write:  1  | 0    | 2

    final Stream stream = startStream(1, 6);
    verifyFlush(stream(stream).headers().estimate(3).write(3));
    assertThat(stream.remoteWindow, is(0));
    verifyRemoteStreamWindowUpdate(2, stream);
    verifyFlush(stream(stream).estimate(2).write(2));
    assertThat(stream.remoteWindow, is(0));
    verifyRemoteStreamWindowUpdate(3, stream);
    verifyFlush(stream(stream).estimate(1).write(1, END_OF_STREAM));
    assertThat(stream.remoteWindow, is(2));
  }

  @Test
  public void testOneStreamWindowExhaustedStreamWithTwoHappyStreams() throws Exception {

    controller.remoteInitialStreamWindowSizeUpdate(10);

    final Stream exhausted = startStream(1, 20);
    verifyFlush(stream(exhausted).headers().estimate(10).write(10));
    final Stream happy1 = startStream(3, 5);
    final Stream happy2 = startStream(5, 10);
    verifyFlush(
        stream(happy1).headers().estimate(5).write(5, END_OF_STREAM),
        stream(happy2).headers().estimate(10).write(10, END_OF_STREAM));
  }

  @Test
  public void testTwoStreamWindowExhaustedStreamsWithOneHappyStream() throws Exception {

    controller.remoteInitialStreamWindowSizeUpdate(10);

    final Stream exhausted1 = startStream(1, 20);
    final Stream happy = startStream(3, 5);
    final Stream exhausted2 = startStream(5, 30);
    verifyFlush(
        stream(exhausted1).headers().estimate(10).write(10),
        stream(happy).headers().estimate(5).write(5, END_OF_STREAM),
        stream(exhausted2).headers().estimate(10).write(10));
  }

  @Test
  public void testStreamAndConnectionWindowExhaustedStreams() throws Exception {

    final int remoteConnectionWindow = 20;
    final int remoteInitialStreamWindow = 10;
    controller = new FlowController<>(remoteConnectionWindow, remoteInitialStreamWindow);

    // Exhaust the window of two streams and the connection window
    final Stream streamWindowExhausted1 = startStream(1, 20);
    final Stream streamWindowExhausted2 = startStream(3, 20);
    verifyFlush(
        stream(streamWindowExhausted1).headers().estimate(10).write(10),
        stream(streamWindowExhausted2).headers().estimate(10).write(10));
    assertThat(controller.remoteConnectionWindow(), is(0));

    // Update the connection window with enough for the first stream to complete
    verifyRemoteConnectionWindowUpdate(15, streamWindowExhausted1, streamWindowExhausted2);

    // Stream windows are still exhausted
    verifyFlush();

    // Feed stream windows
    verifyRemoteStreamWindowUpdate(5, streamWindowExhausted1);
    verifyFlush(stream(streamWindowExhausted1).estimate(5).write(5));
    verifyRemoteStreamWindowUpdate(5, streamWindowExhausted2);
    verifyRemoteStreamWindowUpdate(5, streamWindowExhausted1);
    verifyFlush(
        stream(streamWindowExhausted2).estimate(5).write(5),
        stream(streamWindowExhausted1).estimate(5).write(5, END_OF_STREAM));

    assertThat(controller.remoteConnectionWindow(), is(0));
    assertThat(streamWindowExhausted2.remoteWindow, is(0));

    // Connection window is still exhausted
    verifyFlush();

    // Finish it
    verifyRemoteConnectionWindowUpdate(20, streamWindowExhausted2);

    // Stream window is still exhausted
    verifyFlush();

    // Feed stream window and finish stream
    verifyRemoteStreamWindowUpdate(10, streamWindowExhausted2);
    verifyFlush(stream(streamWindowExhausted2).estimate(5).write(5, END_OF_STREAM));
  }

  @Test
  public void testConnectionWindowExhaustionSingleStream() throws Exception {

    controller = new FlowController<>(3, DEFAULT_INITIAL_WINDOW_SIZE);

    // Three frames:
    // Operation  | Data | Connection Window
    // ----------------------------------
    //            | 6    | 3
    // Write:  3  | 3    | 0
    // Update: 2  | 3    | 2
    // Write:  2  | 1    | 0
    // Update: 3  | 1    | 3
    // Write:  1  | 0    | 2

    final Stream stream = startStream(1, 6);
    verifyFlush(stream(stream).headers().estimate(3).write(3));
    verifyRemoteConnectionWindowUpdate(2, stream);
    verifyFlush(stream(stream).estimate(2).write(2));
    verifyRemoteConnectionWindowUpdate(3, stream);
    verifyFlush(stream(stream).estimate(1).write(1, END_OF_STREAM));
  }

  private Stream startStream(final int id, final int size) {
    final ByteBuf data = randomByteBuf(size);
    final Stream stream = new Stream(id, data);
    controller.start(stream);
    return stream;
  }

  private void verifyRemoteStreamWindowUpdate(final int windowIncrease, final Stream stream) throws Http2Exception {
    final int currentRemoteWindow = stream.remoteWindow;
    final int expectedRemoteWindow = currentRemoteWindow + windowIncrease;
    controller.remoteStreamWindowUpdate(stream, windowIncrease);
    assertThat(stream.remoteWindow, is(expectedRemoteWindow));
  }

  private void verifyRemoteConnectionWindowUpdate(final int bytes, final Stream... streams) throws Http2Exception {
    verifyRemoteConnectionWindowUpdate(bytes, asList(streams));
  }

  private void verifyRemoteConnectionWindowUpdate(final int bytes, final List<Stream> streams) throws Http2Exception {
    // Record window sizes before update
    final int remoteConnectionWindowPre = controller.remoteConnectionWindow();
    final IdentityHashMap<Stream, Integer> remoteStreamWindowsPre = new IdentityHashMap<>();
    for (final Stream stream : streams) {
      remoteStreamWindowsPre.put(stream, stream.remoteWindow);
    }

    // Update the connection window
    controller.remoteConnectionWindowUpdate(bytes);

    // Verify that the remote connection window was updated
    assertThat(controller.remoteConnectionWindow(), is(remoteConnectionWindowPre + bytes));

    // Verify that stream windows are unchanged
    for (final Stream stream : streams) {
      assertThat(stream.remoteWindow, is(remoteStreamWindowsPre.get(stream)));
    }
  }

  private void verifyFlush(final FlushOp... ops) throws Http2Exception {
    verifyFlush(asList(ops));
  }

  private void verifyFlush(final List<FlushOp> ops)
      throws Http2Exception {

    reset(writer);

    // Record window sizes before write
    final int remoteConnectionWindowPre = controller.remoteConnectionWindow();
    final IdentityHashMap<Stream, Integer> remoteStreamWindowsPre = new IdentityHashMap<>();
    for (final FlushOp op : ops) {
      remoteStreamWindowsPre.put(op.stream, op.stream.remoteWindow);
    }

    // Prepare the mock writer
    int expectedMinBufferSize = 0;
    for (final FlushOp op : ops) {
      if (op.headers) {
        final int estimatedInitialHeadersFrameSize = ThreadLocalRandom.current().nextInt(32, 128);
        expectedMinBufferSize += estimatedInitialHeadersFrameSize;
        when(writer.estimateInitialHeadersFrameSize(ctx, op.stream)).thenReturn(estimatedInitialHeadersFrameSize);
      }
      for (final int estimate : op.estimates) {
        final int estimatedDataFrameSize = ThreadLocalRandom.current().nextInt(32, 128);
        expectedMinBufferSize += estimatedDataFrameSize;
        when(writer.estimateDataFrameSize(ctx, op.stream, estimate)).thenReturn(estimatedDataFrameSize);
      }
    }
    final ByteBuf buf = Unpooled.buffer(expectedMinBufferSize);
    when(writer.writeStart(eq(ctx), bufferSizeCaptor.capture()))
        .thenAnswer(i -> Unpooled.buffer(i.getArgumentAt(1, Integer.class)));

    final InOrder inOrder = inOrder(writer);

    // Flush the streams
    controller.flush(ctx, writer);

    // Verify that header and data frame estimations come first, in stream order
    for (final FlushOp op : ops) {
      if (op.headers) {
        inOrder.verify(writer).estimateInitialHeadersFrameSize(ctx, op.stream);
      }
      for (final int estimate : op.estimates) {
        inOrder.verify(writer).estimateDataFrameSize(ctx, op.stream, estimate);
      }
    }

    // Verify that the write phase started if there is anything to write
    final int expectedWritesBytes = ops.stream()
        .mapToInt(op -> op.writes.stream()
            .mapToInt(w -> w.bytes * w.times)
            .sum())
        .sum();
    if (ops.size() > 0) {
      inOrder.verify(writer).writeStart(eq(ctx),
                                        intThat(greaterThanOrEqualTo(expectedMinBufferSize)));
      final int bufferSize = bufferSizeCaptor.getValue();
      assertThat(bufferSize, is(greaterThanOrEqualTo(expectedMinBufferSize)));
    }

    // Verify expected data header and frame writes
    for (final FlushOp op : ops) {
      if (op.headers) {
        final boolean endOfStream = op.headerFlags.contains(END_OF_STREAM);
        inOrder.verify(writer).writeInitialHeadersFrame(ctx, buf, op.stream, endOfStream);
        if (endOfStream) {
          inOrder.verify(writer).streamEnd(op.stream);
        }
      }
      for (final FlushOp.Write write : op.writes) {
        final boolean endOfStream = write.flags.contains(END_OF_STREAM);
        inOrder.verify(writer, times(write.times)).writeDataFrame(ctx, buf, op.stream, write.bytes, endOfStream);
        if (endOfStream) {
          inOrder.verify(writer).streamEnd(op.stream);
        }
      }
    }

    // Verify that the write ended if there was anything to write
    if (ops.size() > 0) {
      inOrder.verify(writer).writeEnd(ctx, buf);
    }
    verifyNoMoreInteractions(writer);

    // Consume written bytes
    for (final FlushOp op : ops) {
      for (final FlushOp.Write write : op.writes) {
        op.stream.data.skipBytes(write.bytes * write.times);
      }
    }

    // Verify that the windows have been updated appropriately
    final int remoteConnectionWindowConsumption = expectedWritesBytes;
    assertThat(controller.remoteConnectionWindow(), is(remoteConnectionWindowPre - remoteConnectionWindowConsumption));
    for (final FlushOp op : ops) {
      final int streamWindowConsumption = op.writes.stream()
          .mapToInt(w -> w.bytes * w.times)
          .sum();
      final int expectedStreamWindow = remoteStreamWindowsPre.get(op.stream) - streamWindowConsumption;
      assertThat(op.stream.remoteWindow, is(expectedStreamWindow));
    }

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);
  }

  private FlushOp stream(final Stream stream) {
    return new FlushOp(stream);
  }

  static class FlushOp {

    static class Write {
      private final int bytes;
      private final int times;
      private Set<Flags> flags;

      Write(final int bytes, final int times, final Set<Flags> flags) {
        this.bytes = bytes;
        this.times = times;
        this.flags = flags;
      }
    }

    private final Stream stream;
    private boolean headers;
    private Set<Flags> headerFlags;
    private List<Integer> estimates = new ArrayList<>();
    private List<Write> writes = new ArrayList<>();

    FlushOp(final Stream stream) {

      this.stream = stream;
    }

    FlushOp headers(final Flags... flags) {
      return headers(true, flags);
    }

    FlushOp headers(final boolean headers, final Flags... flags) {
      this.headers = headers;
      this.headerFlags = ImmutableSet.copyOf(flags);
      return this;
    }

    FlushOp estimate(final int bytes) {
      this.estimates.add(bytes);
      return this;
    }

    FlushOp write(final int bytes, final int times, final Flags... flags) {
      this.writes.add(new Write(bytes, times, ImmutableSet.copyOf(flags)));
      return this;
    }

    FlushOp write(final int bytes, final Flags... flags) {
      this.writes.add(new Write(bytes, 1, ImmutableSet.copyOf(flags)));
      return this;
    }

    enum Flags {
      END_OF_STREAM
    }
  }

}