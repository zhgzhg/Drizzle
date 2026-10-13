package com.github.zhgzhg.drizzle.remote;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Copies everything printed to {@code System.out} and {@code System.err} to a log as it's printed, while still letting
 * it reach the original streams (in Arduino IDE: the editor's console). Lines printed to stderr are also collected
 * separately.
 * <p>
 * The streams are JVM-wide, so output of unrelated threads printed meanwhile gets captured as well.
 */
public final class OutputCapture implements AutoCloseable {
    private static final int MAX_ERROR_LINES = 500;

    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final Tee outTee;
    private final Tee errTee;
    private final List<String> errorLines = Collections.synchronizedList(new ArrayList<>());

    private OutputCapture(final Consumer<String> log) {
        this.originalOut = System.out;
        this.originalErr = System.err;
        this.outTee = new Tee(originalOut, log, null);
        this.errTee = new Tee(originalErr, log, line -> {
            if (errorLines.size() < MAX_ERROR_LINES) errorLines.add(line);
        });
    }

    public static OutputCapture start(final Consumer<String> log) {
        OutputCapture capture = new OutputCapture(log);
        // PrintStream encodes with the default charset, which is what the tees decode with
        System.setOut(new PrintStream(capture.outTee, true));
        System.setErr(new PrintStream(capture.errTee, true));
        return capture;
    }

    /**
     * @return the lines printed to stderr so far, without line terminators
     */
    public List<String> errorLines() {
        synchronized (errorLines) {
            return new ArrayList<>(errorLines);
        }
    }

    @Override
    public void close() {
        System.out.flush();
        System.err.flush();
        System.setOut(originalOut);
        System.setErr(originalErr);
        outTee.finish();
        errTee.finish();
    }

    private static final class Tee extends OutputStream {
        private final OutputStream target;
        private final Consumer<String> log;
        private final Consumer<String> lineSink;
        private final CharsetDecoder decoder = Charset.defaultCharset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private ByteBuffer incomplete = ByteBuffer.allocate(0); // the start of a character split between writes
        private final StringBuilder line = new StringBuilder();

        Tee(final OutputStream target, final Consumer<String> log, final Consumer<String> lineSink) {
            this.target = target;
            this.log = log;
            this.lineSink = lineSink;
        }

        @Override
        public void write(final int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(final byte[] b, final int off, final int len) throws IOException {
            target.write(b, off, len);
            decode(b, off, len, false);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        synchronized void finish() {
            decode(new byte[0], 0, 0, true);
            if (lineSink != null && line.length() > 0) {
                lineSink.accept(line.toString());
                line.setLength(0);
            }
        }

        private void decode(final byte[] b, final int off, final int len, final boolean endOfInput) {
            // the (Buffer) casts keep the bytecode Java 8 compatible even when built with a newer JDK
            ByteBuffer in = ByteBuffer.allocate(incomplete.remaining() + len);
            in.put(incomplete).put(b, off, len);
            ((Buffer) in).flip();

            CharBuffer out = CharBuffer.allocate((int) (in.remaining() * (double) decoder.maxCharsPerByte()) + 4);
            decoder.decode(in, out, endOfInput);
            if (endOfInput) decoder.flush(out);

            incomplete = ByteBuffer.allocate(in.remaining());
            incomplete.put(in);
            ((Buffer) incomplete).flip();

            ((Buffer) out).flip();
            if (!out.hasRemaining()) return;

            String text = out.toString();
            log.accept(text);
            if (lineSink != null) collectLines(text);
        }

        private void collectLines(final String text) {
            for (int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                if (c == '\n') {
                    int end = line.length();
                    if (end > 0 && line.charAt(end - 1) == '\r') line.setLength(end - 1);
                    lineSink.accept(line.toString());
                    line.setLength(0);
                } else {
                    line.append(c);
                }
            }
        }
    }
}
