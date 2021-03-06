package io.norberg.http2;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static io.norberg.http2.PseudoHeaders.AUTHORITY;
import static io.norberg.http2.PseudoHeaders.METHOD;
import static io.norberg.http2.PseudoHeaders.PATH;
import static io.norberg.http2.PseudoHeaders.SCHEME;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.twitter.hpack.Decoder;
import com.twitter.hpack.HeaderListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HpackEncoderTest {

  @Mock HeaderListener listener;

  private static final AsciiString FOO = AsciiString.of("foo");
  private static final AsciiString BAR = AsciiString.of("bar");
  private static final AsciiString BAZ = AsciiString.of("baz");

  @Test
  public void testEncodeStatic() throws Exception {

    // Encode
    final HpackEncoder encoder = new HpackEncoder(0);
    final ByteBuf block = Unpooled.buffer();
    encoder.encodeHeader(block, METHOD, GET.asciiName(), false);

    // Decode
    final InputStream is = new ByteBufInputStream(block);
    final Decoder decoder = new Decoder(1024, 1024);
    decoder.decode(is, listener);

    verify(listener).addHeader(METHOD.array(), GET.asciiName().array(), false);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void testIndexing() throws Exception {

    final HpackEncoder encoder = new HpackEncoder(Integer.MAX_VALUE);
    final Decoder decoder = new Decoder(1024, 1024);

    final ByteBuf block1 = Unpooled.buffer();
    final ByteBuf block2 = Unpooled.buffer();

    // Insert into dynamic table
    encoder.encodeHeader(block1, FOO, BAR, false);
    encoder.encodeHeader(block1, BAR, FOO, false);
    encoder.encodeHeader(block1, FOO, BAZ, false);
    assertThat(encoder.tableLength(), is(3));

    // Use dynamic table
    encoder.encodeHeader(block2, BAR, FOO, false);
    encoder.encodeHeader(block2, FOO, BAZ, false);
    encoder.encodeHeader(block2, FOO, BAR, false);
    assertThat(block2.readableBytes(), is(lessThan(block1.readableBytes())));

    // Decode first block
    final InputStream is1 = new ByteBufInputStream(block1);
    decoder.decode(is1, listener);
    verify(listener).addHeader(FOO.array(), BAR.array(), false);
    verify(listener).addHeader(BAR.array(), FOO.array(), false);
    verify(listener).addHeader(FOO.array(), BAZ.array(), false);
    verifyNoMoreInteractions(listener);
    reset(listener);

    // Decode second block
    final InputStream is2 = new ByteBufInputStream(block2);
    decoder.decode(is2, listener);
    verify(listener).addHeader(FOO.array(), BAR.array(), false);
    verify(listener).addHeader(BAR.array(), FOO.array(), false);
    verify(listener).addHeader(FOO.array(), BAZ.array(), false);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void testEncodeRequest() throws Exception {
    final AsciiString method = GET.asciiName();
    final AsciiString scheme = HTTPS.name();
    final AsciiString authority = AsciiString.of("www.test.com");
    final AsciiString path = AsciiString.of("/");

    // Encode
    final HpackEncoder encoder = new HpackEncoder(1024);
    final ByteBuf block = Unpooled.buffer();
    encoder.encodeRequest(block, method, scheme, authority, path);

    final InputStream is = new ByteBufInputStream(block);
    final Decoder decoder = new Decoder(1024, 1024);
    decoder.decode(is, listener);

    verify(listener).addHeader(METHOD.array(), method.array(), false);
    verify(listener).addHeader(SCHEME.array(), scheme.array(), false);
    verify(listener).addHeader(AUTHORITY.array(), authority.array(), false);
    verify(listener).addHeader(PATH.array(), path.array(), false);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void testOverflow() throws Exception {
    final int n = 16 * 1024;
    final ByteBuf block = Unpooled.buffer();
    final HpackEncoder encoder = new HpackEncoder(4096);
    for (int i = 0; i < n; i++) {
      encoder.encodeHeader(block, AsciiString.of("header" + i), AsciiString.of("value" + i));
    }

    // TODO: verify desired result
  }
}