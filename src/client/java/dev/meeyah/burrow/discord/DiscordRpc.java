package dev.meeyah.burrow.discord;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight, zero-dependency Discord Rich Presence client speaking Discord's
 * local IPC protocol directly over Unix domain sockets (Linux/macOS, including
 * Flatpak, Vesktop & Snap paths) or Windows named pipes.
 *
 * All I/O runs on a single daemon worker thread so it never stalls rendering
 * or hangs game shutdown. Respects Burrow's Incognito mode & config toggle.
 */
public final class DiscordRpc {
    public static final String APPLICATION_ID = "1573020622852853760";

    private static final Logger LOG = LoggerFactory.getLogger("Burrow/DiscordRPC");
    private static final Gson GSON = new Gson();
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;
    private static final int OP_PING = 3;
    private static final int OP_PONG = 4;
    private static final int MAX_FRAME_LEN = 1024 * 1024;

    public record Presence(
            String details,
            @Nullable String state,
            long startEpochSeconds,
            int partySize,
            int partyMax,
            String largeText
    ) {}

    private interface IpcTransport extends AutoCloseable {
        void writeFully(byte[] data) throws IOException;
        void readFully(byte[] buf) throws IOException;
        @Override void close();
    }

    private static final Object LOCK = new Object();
    private static @Nullable ScheduledExecutorService executor;
    private static @Nullable IpcTransport transport;
    private static volatile boolean running = false;
    private static volatile @Nullable Presence desiredPresence = null;
    private static @Nullable Presence lastSentPresence = null;
    private static boolean hasSentInitial = false;
    private static long lastConnectAttemptMs = 0L;

    private DiscordRpc() {}

    public static void start() {
        synchronized (LOCK) {
            if (running) {
                return;
            }
            running = true;
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "burrow-discord-rpc");
                t.setDaemon(true);
                return t;
            });
            // Periodically retry connecting if Discord opens after Minecraft.
            executor.scheduleWithFixedDelay(DiscordRpc::tickReconnect, 1, 30, TimeUnit.SECONDS);
        }
    }

    public static void setPresence(@Nullable Presence presence) {
        desiredPresence = presence;
        ScheduledExecutorService exec = executor;
        if (running && exec != null && !exec.isShutdown()) {
            exec.execute(DiscordRpc::syncNow);
        }
    }

    public static void clear() {
        setPresence(null);
    }

    public static void stop() {
        ScheduledExecutorService exec;
        synchronized (LOCK) {
            if (!running) {
                return;
            }
            running = false;
            desiredPresence = null;
            exec = executor;
            executor = null;
        }
        if (exec != null) {
            try {
                exec.submit(() -> {
                    sendClearAndClose();
                }).get(750, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                closeTransportQuietly();
            } finally {
                exec.shutdownNow();
            }
        } else {
            closeTransportQuietly();
        }
    }

    private static void tickReconnect() {
        if (!running) {
            return;
        }
        if (transport == null) {
            long now = System.currentTimeMillis();
            if (now - lastConnectAttemptMs < 15_000L) {
                return;
            }
            lastConnectAttemptMs = now;
            if (connectAndHandshake()) {
                hasSentInitial = false;
                syncNow();
            }
        }
    }

    private static void syncNow() {
        if (!running) {
            return;
        }
        Presence target = desiredPresence;
        if (transport == null) {
            if (target == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastConnectAttemptMs < 15_000L) {
                return;
            }
            lastConnectAttemptMs = now;
            if (!connectAndHandshake()) {
                return;
            }
            hasSentInitial = false;
        }

        if (hasSentInitial && Objects.equals(target, lastSentPresence)) {
            return;
        }

        try {
            sendSetActivity(target);
            lastSentPresence = target;
            hasSentInitial = true;
        } catch (IOException e) {
            LOG.debug("Discord IPC disconnected while updating presence: {}", e.getMessage());
            closeTransportQuietly();
            hasSentInitial = false;
            lastSentPresence = null;
        }
    }

    private static void sendClearAndClose() {
        if (transport != null) {
            try {
                sendSetActivity(null);
            } catch (IOException ignored) {
            }
            try {
                writeFrame(transport, OP_CLOSE, "{}");
            } catch (IOException ignored) {
            }
        }
        closeTransportQuietly();
        hasSentInitial = false;
        lastSentPresence = null;
    }

    private static boolean connectAndHandshake() {
        IpcTransport opened = openIpc();
        if (opened == null) {
            return false;
        }
        try {
            JsonObject handshake = new JsonObject();
            handshake.addProperty("v", 1);
            handshake.addProperty("client_id", APPLICATION_ID);
            writeFrame(opened, OP_HANDSHAKE, GSON.toJson(handshake));

            Frame reply = readFrame(opened);
            if (reply.opcode == OP_CLOSE) {
                opened.close();
                return false;
            }
            transport = opened;
            LOG.info("Connected to Discord Rich Presence IPC");
            startReaderThread(opened);
            return true;
        } catch (IOException e) {
            opened.close();
            LOG.debug("Discord IPC handshake failed: {}", e.getMessage());
            return false;
        }
    }

    private static void startReaderThread(IpcTransport conn) {
        Thread reader = new Thread(() -> {
            try {
                while (running && transport == conn) {
                    Frame f = readFrame(conn);
                    if (f.opcode == OP_PING) {
                        synchronized (conn) {
                            writeFrame(conn, OP_PONG, f.payload);
                        }
                    } else if (f.opcode == OP_CLOSE) {
                        break;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (transport == conn) {
                    closeTransportQuietly();
                    hasSentInitial = false;
                    lastSentPresence = null;
                }
            }
        }, "burrow-discord-rpc-read");
        reader.setDaemon(true);
        reader.start();
    }

    private static void sendSetActivity(@Nullable Presence p) throws IOException {
        IpcTransport conn = transport;
        if (conn == null) {
            return;
        }

        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());

        if (p == null) {
            args.add("activity", JsonNull.INSTANCE);
        } else {
            JsonObject activity = new JsonObject();
            if (p.details() != null && !p.details().isBlank()) {
                activity.addProperty("details", clamp(p.details(), 128));
            }
            if (p.state() != null && !p.state().isBlank()) {
                activity.addProperty("state", clamp(p.state(), 128));
            }
            if (p.startEpochSeconds() > 0) {
                JsonObject timestamps = new JsonObject();
                timestamps.addProperty("start", p.startEpochSeconds());
                activity.add("timestamps", timestamps);
            }
            if (p.partySize() > 0 && p.partyMax() >= p.partySize()) {
                JsonObject party = new JsonObject();
                party.addProperty("id", "burrow-" + Long.toHexString(ProcessHandle.current().pid()));
                JsonArray size = new JsonArray();
                size.add(p.partySize());
                size.add(p.partyMax());
                party.add("size", size);
                activity.add("party", party);
            }
            JsonObject assets = new JsonObject();
            assets.addProperty("large_image", "burrow");
            if (p.largeText() != null && !p.largeText().isBlank()) {
                assets.addProperty("large_text", clamp(p.largeText(), 128));
            }
            activity.add("assets", assets);
            args.add("activity", activity);
        }

        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());

        synchronized (conn) {
            writeFrame(conn, OP_FRAME, GSON.toJson(cmd));
        }
    }

    private static void writeFrame(IpcTransport conn, int opcode, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(opcode);
        buf.putInt(payload.length);
        buf.put(payload);
        conn.writeFully(buf.array());
    }

    private record Frame(int opcode, String payload) {}

    private static Frame readFrame(IpcTransport conn) throws IOException {
        byte[] header = new byte[8];
        conn.readFully(header);
        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = hb.getInt();
        int len = hb.getInt();
        if (len < 0 || len > MAX_FRAME_LEN) {
            throw new IOException("Invalid Discord IPC frame length: " + len);
        }
        byte[] body = new byte[len];
        if (len > 0) {
            conn.readFully(body);
        }
        return new Frame(opcode, new String(body, StandardCharsets.UTF_8));
    }

    private static @Nullable IpcTransport openIpc() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return openWindowsPipe();
        }
        return openUnixSocket();
    }

    private static @Nullable IpcTransport openWindowsPipe() {
        for (int i = 0; i < 10; i++) {
            String pipePath = "\\\\.\\pipe\\discord-ipc-" + i;
            try {
                RandomAccessFile raf = new RandomAccessFile(pipePath, "rw");
                return new IpcTransport() {
                    @Override
                    public void writeFully(byte[] data) throws IOException {
                        raf.write(data);
                    }

                    @Override
                    public void readFully(byte[] buf) throws IOException {
                        raf.readFully(buf);
                    }

                    @Override
                    public void close() {
                        try {
                            raf.close();
                        } catch (IOException ignored) {
                        }
                    }
                };
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static @Nullable IpcTransport openUnixSocket() {
        List<Path> dirs = new ArrayList<>();
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        if (xdg != null && !xdg.isBlank()) {
            Path p = Path.of(xdg);
            dirs.add(p);
            dirs.add(p.resolve("app/com.discordapp.Discord"));
            dirs.add(p.resolve("app/dev.vencord.Vesktop"));
            dirs.add(p.resolve("snap.discord"));
        }
        String tmpdir = System.getenv("TMPDIR");
        if (tmpdir != null && !tmpdir.isBlank()) {
            dirs.add(Path.of(tmpdir));
        }
        dirs.add(Path.of("/tmp"));

        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (int i = 0; i < 10; i++) {
                Path sock = dir.resolve("discord-ipc-" + i);
                if (!Files.exists(sock)) {
                    continue;
                }
                try {
                    SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                    ch.connect(UnixDomainSocketAddress.of(sock));
                    return new IpcTransport() {
                        @Override
                        public void writeFully(byte[] data) throws IOException {
                            ByteBuffer bb = ByteBuffer.wrap(data);
                            while (bb.hasRemaining()) {
                                ch.write(bb);
                            }
                        }

                        @Override
                        public void readFully(byte[] buf) throws IOException {
                            ByteBuffer bb = ByteBuffer.wrap(buf);
                            while (bb.hasRemaining()) {
                                int n = ch.read(bb);
                                if (n < 0) {
                                    throw new EOFException("Discord IPC socket closed");
                                }
                            }
                        }

                        @Override
                        public void close() {
                            try {
                                ch.close();
                            } catch (IOException ignored) {
                            }
                        }
                    };
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    private static void closeTransportQuietly() {
        IpcTransport t = transport;
        transport = null;
        if (t != null) {
            t.close();
        }
    }

    public static long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
