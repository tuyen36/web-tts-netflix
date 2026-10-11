package com.example.webtts;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client WebSocket goi Edge TTS, port tu Communicate.__stream() Python.
 *
 * - 1 cau = 1 ket noi WS moi (giong Python: moi partial_text 1 stream).
 * - Gui speech.config + ssml, nhan binary Path:audio ghep thanh mp3.
 * - Ket thuc khi nhan text Path:turn.end.
 */
public class EdgeTtsClient {

    public interface Callback {
        void onAudio(byte[] mp3);
        void onError(String msg);
    }

    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int RECEIVE_TIMEOUT_SEC = 60;

    public static void synthesize(String text, String voice, String rate,
                                  Callback cb) {
        new Thread(() -> {
            try {
                doSynth(text, voice, rate, cb);
            } catch (Exception e) {
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "EdgeTts").start();
    }

    private static void doSynth(String text, String voice, String rate,
                                Callback cb) throws Exception {
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final AtomicBoolean gotAudio = new AtomicBoolean(false);
        final AtomicBoolean gotEnd = new AtomicBoolean(false);
        final CountDownLatch done = new CountDownLatch(1);
        final String[] err = {null};

        Map<String, String> headers = new HashMap<>();
        headers.put("Pragma", "no-cache");
        headers.put("Cache-Control", "no-cache");
        headers.put("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Sec-WebSocket-Version", "13");

        WebSocketClient ws = new WebSocketClient(new URI(EdgeProtocol.wsUrl()), headers) {
            @Override public void onOpen(ServerHandshake h) {
                try {
                    send(EdgeProtocol.speechConfigRequest());
                    String ssml = EdgeProtocol.mkssml(voice, rate, text);
                    send(EdgeProtocol.ssmlRequest(ssml));
                } catch (Exception e) {
                    err[0] = e.getMessage();
                    done.countDown();
                }
            }

            @Override public void onMessage(String message) {
                try {
                    int sep = message.indexOf("\r\n\r\n");
                    String head = sep >= 0 ? message.substring(0, sep) : message;
                    if (head.contains("Path:turn.end")) {
                        gotEnd.set(true);
                        done.countDown();
                    }
                    // Path:audio.metadata / response / turn.start: bo qua.
                } catch (Exception ignored) {}
            }

            @Override public void onMessage(ByteBuffer bytes) {
                try {
                    byte[] raw = new byte[bytes.remaining()];
                    bytes.get(raw);
                    if (raw.length < 2) return;
                    int headerLen = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
                    if (headerLen + 2 > raw.length) return;
                    String head = new String(raw, 0, headerLen,
                            StandardCharsets.UTF_8);
                    if (!head.contains("Path:audio")) return;
                    int dataStart = headerLen + 2;
                    if (dataStart >= raw.length) return;
                    // Cuoi stream service gui binary rong: bo qua.
                    if (!head.contains("Content-Type")) return;
                    audio.write(raw, dataStart, raw.length - dataStart);
                    gotAudio.set(true);
                } catch (Exception ignored) {}
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                done.countDown();
            }

            @Override public void onError(Exception ex) {
                err[0] = ex.getMessage();
            }
        };
        ws.setConnectionLostTimeout(0);
        if (!ws.connectBlocking(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            cb.onError("WS connect timeout");
            return;
        }
        boolean finished = done.await(RECEIVE_TIMEOUT_SEC, TimeUnit.SECONDS);
        try {
            ws.closeBlocking();
        } catch (Exception ignored) {}
        if (gotAudio.get() && audio.size() > 0) {
            cb.onAudio(audio.toByteArray());
        } else if (err[0] != null) {
            cb.onError("Edge: " + err[0]);
        } else if (!finished) {
            cb.onError("Edge timeout 60s");
        } else {
            cb.onError("Edge: no audio");
        }
    }
}
