package agentorders;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Minimal pooled Redis (RESP2) client over plain sockets, enough for EVAL/GET. Sample-grade:
 * no TLS, AUTH, cluster, or pipelining. Use Lettuce/Jedis behind RedisOps in production.
 */
public final class RespClient implements RedisOps, AutoCloseable {
    public static final class RedisException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public RedisException(String msg) { super(msg); }
        public RedisException(Throwable cause) { super(cause); }
    }

    private static final class Conn {
        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Conn(Socket s) throws IOException {
            socket = s;
            in = new BufferedInputStream(s.getInputStream(), 8192);
            out = s.getOutputStream();
        }

        void close() {
            try { socket.close(); } catch (IOException ignore) { /* closing */ }
        }
    }

    private final String host;
    private final int port;
    private final int timeoutMs;
    private final ArrayBlockingQueue<Conn> idle;

    public RespClient(String host, int port, int poolSize, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.idle = new ArrayBlockingQueue<>(poolSize);
    }

    /** Returns a connected client, or null if no server answers PING at host:port. */
    public static RespClient tryConnect(String host, int port) {
        RespClient c = new RespClient(host, port, 32, 2000);
        try {
            return "PONG".equals(c.command("PING")) ? c : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Conn open() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        return new Conn(s);
    }

    public Object command(Object... args) {
        Conn c = idle.poll();
        try {
            if (c == null) c = open();
            c.out.write(encode(args));
            c.out.flush();
            Object reply = read(c.in);
            if (!idle.offer(c)) c.close();
            return reply;
        } catch (RedisException e) {        // server-side error reply: connection is still healthy
            if (c != null && !idle.offer(c)) c.close();
            throw e;
        } catch (IOException e) {
            if (c != null) c.close();
            throw new RedisException(e);
        }
    }

    @Override
    public Object eval(String script, List<String> keys, List<String> args) {
        List<Object> cmd = new ArrayList<>();
        cmd.add("EVAL");
        cmd.add(script);
        cmd.add(keys.size());
        cmd.addAll(keys);
        cmd.addAll(args);
        return command(cmd.toArray());
    }

    @Override
    public String get(String key) {
        return (String) command("GET", key);
    }

    @Override
    public void close() {
        Conn c;
        while ((c = idle.poll()) != null) c.close();
    }

    // ------------------------------------------------------------------ protocol
    private static byte[] encode(Object[] args) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        writeAscii(b, "*" + args.length + "\r\n");
        for (Object a : args) {
            byte[] bytes = String.valueOf(a).getBytes(StandardCharsets.UTF_8);
            writeAscii(b, "$" + bytes.length + "\r\n");
            b.writeBytes(bytes);
            writeAscii(b, "\r\n");
        }
        return b.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream b, String s) {
        b.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != '\r') {
            if (c < 0) throw new EOFException();
            sb.append((char) c);
        }
        if (in.read() != '\n') throw new IOException("malformed RESP line");
        return sb.toString();
    }

    private static Object read(InputStream in) throws IOException {
        int t = in.read();
        if (t < 0) throw new EOFException();
        switch (t) {
            case '+': return line(in);
            case '-': throw new RedisException(line(in));
            case ':': return Long.parseLong(line(in));
            case '$': {
                int n = Integer.parseInt(line(in));
                if (n < 0) return null;
                byte[] b = in.readNBytes(n);
                in.skipNBytes(2);
                return new String(b, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line(in));
                if (n < 0) return null;
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) l.add(read(in));
                return l;
            }
            default: throw new IOException("unexpected RESP type " + (char) t);
        }
    }
}
