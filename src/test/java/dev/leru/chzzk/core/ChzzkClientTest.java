package dev.leru.chzzk.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ChzzkClientTest {
    private static final String SOURCE = "0123456789abcdef0123456789abcdef";

    @Test
    void mockNetworkEmitsOnlyRealParsedDonationAfterAck() throws Exception {
        FakeConnector connector = new FakeConnector();
        List<RawDonation> donations = new ArrayList<>();
        CountDownLatch donationLatch = new CountDownLatch(1);
        Consumer<RawDonation> onDonation = donation -> {
            synchronized (donations) {
                donations.add(donation);
            }
            donationLatch.countDown();
        };
        ChzzkClient.EndpointResolver resolver = (sourceHash, httpClient) -> new ChzzkClient.ResolvedChat("chat-channel", "secret-token");
        ChzzkClient client = new ChzzkClient(SOURCE, onDonation, status -> {}, HttpClient.newHttpClient(), resolver, connector);

        client.start();
        assertTrue(connector.connected.await(2, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> connector.socket.sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":100") && text.contains("\"accTkn\":\"secret-token\""))));

        connector.listener.get().onText(connector.socket, "{\"cmd\":10100,\"retCode\":0,\"bdy\":{}}", true);
        connector.listener.get().onText(connector.socket,
                "{\"cmd\":93102,\"bdy\":[{\"msgSn\":\"d1\",\"profile\":{\"nickname\":\"Donor\"},\"msg\":\"hi\",\"msgTime\":1700000000000,\"payAmount\":1000}]}",
                true);

        assertTrue(donationLatch.await(2, TimeUnit.SECONDS));
        synchronized (donations) {
            assertEquals(1, donations.size());
            assertEquals("chzzk:d1", donations.getFirst().eventId());
        }
        client.close();
        assertTrue(connector.socket.aborted);
    }

    @Test
    void respondsToServerPingWithCmd10000() throws Exception {
        FakeConnector connector = new FakeConnector();
        ChzzkClient.EndpointResolver resolver = (sourceHash, httpClient) -> new ChzzkClient.ResolvedChat("chat-channel", "secret-token");
        ChzzkClient client = new ChzzkClient(SOURCE, donation -> {}, status -> {}, HttpClient.newHttpClient(), resolver, connector);

        client.start();
        assertTrue(connector.connected.await(2, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> connector.socket.sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":100"))));
        connector.listener.get().onText(connector.socket, "{\"cmd\":10100,\"retCode\":0}", true);
        connector.listener.get().onText(connector.socket, "{\"cmd\":0}", true);

        assertTrue(waitUntil(() -> connector.socket.sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":10000"))));
        client.close();
    }

    @Test
    void selfAbortReleasesAwaitClosedAndReconnectsWithoutCloseCallback() throws Exception {
        SequenceConnector connector = new SequenceConnector(2);
        ChzzkClient.EndpointResolver resolver = (sourceHash, httpClient) -> new ChzzkClient.ResolvedChat("chat-channel", "secret-token");
        ChzzkClient client = new ChzzkClient(SOURCE, donation -> {}, status -> {}, HttpClient.newHttpClient(), resolver, connector);

        client.start();
        assertTrue(connector.awaitConnections(1));
        assertTrue(waitUntil(() -> connector.socket(0).sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":100"))));
        connector.listener(0).onText(connector.socket(0), "x".repeat(DonationParser.MAX_FRAME_BYTES + 1), true);

        assertTrue(connector.awaitConnections(2));
        assertTrue(connector.socket(0).aborted);
        client.close();
    }

    @Test
    void staleListenerCannotEmitAfterReconnect() throws Exception {
        SequenceConnector connector = new SequenceConnector(2);
        List<RawDonation> donations = Collections.synchronizedList(new ArrayList<>());
        ChzzkClient.EndpointResolver resolver = (sourceHash, httpClient) -> new ChzzkClient.ResolvedChat("chat-channel", "secret-token");
        ChzzkClient client = new ChzzkClient(SOURCE, donations::add, status -> {}, HttpClient.newHttpClient(), resolver, connector);

        client.start();
        assertTrue(connector.awaitConnections(1));
        assertTrue(waitUntil(() -> connector.socket(0).sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":100"))));
        connector.listener(0).onText(connector.socket(0), "{\"cmd\":10100,\"retCode\":0}", true);
        connector.listener(0).onClose(connector.socket(0), WebSocket.NORMAL_CLOSURE, "test");
        assertTrue(connector.awaitConnections(2));
        assertTrue(waitUntil(() -> connector.socket(1).sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":100"))));
        connector.listener(1).onText(connector.socket(1), "{\"cmd\":10100,\"retCode\":0}", true);

        connector.listener(0).onText(connector.socket(0),
                "{\"cmd\":93102,\"bdy\":[{\"msgSn\":\"stale\",\"msgTime\":1700000000000,\"payAmount\":1000}]}",
                true);
        Thread.sleep(100);

        assertTrue(donations.isEmpty());
        client.close();
    }

    @Test
    void sendFailureReleasesListenerAndReconnects() throws Exception {
        SequenceConnector connector = new SequenceConnector(2);
        ChzzkClient.EndpointResolver resolver = (sourceHash, httpClient) -> new ChzzkClient.ResolvedChat("chat-channel", "secret-token");
        ChzzkClient client = new ChzzkClient(SOURCE, donation -> {}, status -> {}, HttpClient.newHttpClient(), resolver, connector);

        client.start();
        assertTrue(connector.awaitConnections(1));
        assertTrue(waitUntil(() -> connector.socket(0).sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":100"))));
        connector.listener(0).onText(connector.socket(0), "{\"cmd\":10100,\"retCode\":0}", true);
        connector.socket(0).failSends = true;
        connector.listener(0).onText(connector.socket(0), "{\"cmd\":0}", true);

        assertTrue(connector.awaitConnections(2));
        assertTrue(connector.socket(0).aborted);
        client.close();
    }

    @Test
    void doesNotTreatHeartbeatAckAsJoinAck() throws Exception {
        FakeConnector connector = new FakeConnector();
        List<RawDonation> donations = Collections.synchronizedList(new ArrayList<>());
        ChzzkClient.EndpointResolver resolver = (sourceHash, httpClient) -> new ChzzkClient.ResolvedChat("chat-channel", "secret-token");
        ChzzkClient client = new ChzzkClient(SOURCE, donations::add, status -> {}, HttpClient.newHttpClient(), resolver, connector);

        client.start();
        assertTrue(connector.connected.await(2, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> connector.socket.sentTexts.stream().anyMatch(text -> text.contains("\"cmd\":100"))));
        connector.listener.get().onText(connector.socket, "{\"cmd\":10000}", true);
        connector.listener.get().onText(connector.socket,
                "{\"cmd\":93102,\"bdy\":[{\"msgSn\":\"d1\",\"msgTime\":1700000000000,\"payAmount\":1000}]}",
                true);
        Thread.sleep(100);

        assertTrue(donations.isEmpty());
        client.close();
    }

    @Test
    void directResolverTimesOutStalledBodyAfterHeaders() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source/" + SOURCE, exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"content\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            requestStarted.countDown();
            try {
                releaseHandler.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ChzzkClient.EndpointResolver resolver = ChzzkClient.directEndpointResolverForTest(
                    localUrl(server, "/source/%s"), localUrl(server, "/token/%s"), Duration.ofMillis(150));
            long started = System.nanoTime();

            IOException thrown = assertThrows(IOException.class,
                    () -> resolver.resolve(SOURCE, HttpClient.newHttpClient()));

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals("upstream request failed", thrown.getMessage());
            assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
            assertTrue(elapsedMillis < 1_000, "resolver returned after " + elapsedMillis + "ms");
        } finally {
            releaseHandler.countDown();
            server.stop(0);
        }
    }

    @Test
    void directResolverRejectsOversizedResponseBody() throws Exception {
        byte[] oversized = ("{\"content\":\"" + "x".repeat(DonationParser.MAX_FRAME_BYTES) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source/" + SOURCE, exchange -> sendBytes(exchange, 200, oversized));
        server.start();
        try {
            ChzzkClient.EndpointResolver resolver = ChzzkClient.directEndpointResolverForTest(
                    localUrl(server, "/source/%s"), localUrl(server, "/token/%s"), Duration.ofSeconds(2));

            IOException thrown = assertThrows(IOException.class,
                    () -> resolver.resolve(SOURCE, HttpClient.newHttpClient()));

            assertEquals("upstream response too large", thrown.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closeInterruptsAndStopsPendingDirectResolverRequest() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source/" + SOURCE, exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"content\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            requestStarted.countDown();
            try {
                releaseHandler.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        ChzzkClient client = null;
        try {
            ChzzkClient.EndpointResolver resolver = ChzzkClient.directEndpointResolverForTest(
                    localUrl(server, "/source/%s"), localUrl(server, "/token/%s"), Duration.ofSeconds(5));
            client = new ChzzkClient(SOURCE, donation -> {}, status -> {
                if ("stopped".equals(status)) {
                    stopped.countDown();
                }
            }, HttpClient.newHttpClient(), resolver, new FakeConnector());

            client.start();
            assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
            client.close();

            assertTrue(stopped.await(1, TimeUnit.SECONDS));
        } finally {
            if (client != null) {
                client.close();
            }
            releaseHandler.countDown();
            server.stop(0);
        }
    }

    private static boolean waitUntil(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static String localUrl(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void sendBytes(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
        exchange.sendResponseHeaders(statusCode, body.length);
        try (exchange; var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private interface Check {
        boolean ok();
    }

    private static final class SequenceConnector implements ChzzkClient.WebSocketConnector {
        private final CountDownLatch connected;
        private final List<WebSocket.Listener> listeners = Collections.synchronizedList(new ArrayList<>());
        private final List<FakeWebSocket> sockets = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger count = new AtomicInteger();

        private SequenceConnector(int expectedConnections) {
            this.connected = new CountDownLatch(expectedConnections);
        }

        @Override
        public WebSocket connect(URI uri, WebSocket.Listener listener, Duration timeout) {
            assertEquals("wss", uri.getScheme());
            FakeWebSocket socket = new FakeWebSocket();
            sockets.add(socket);
            listeners.add(listener);
            count.incrementAndGet();
            listener.onOpen(socket);
            connected.countDown();
            return socket;
        }

        boolean awaitConnections(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                if (count.get() >= expected) {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        }

        WebSocket.Listener listener(int index) {
            return listeners.get(index);
        }

        FakeWebSocket socket(int index) {
            return sockets.get(index);
        }
    }

    private static final class FakeConnector implements ChzzkClient.WebSocketConnector {
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        final FakeWebSocket socket = new FakeWebSocket();

        @Override
        public WebSocket connect(URI uri, WebSocket.Listener listener, Duration timeout) {
            assertEquals("wss", uri.getScheme());
            this.listener.set(listener);
            listener.onOpen(socket);
            connected.countDown();
            return socket;
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        final List<String> sentTexts = Collections.synchronizedList(new ArrayList<>());
        volatile boolean aborted;
        volatile boolean failSends;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (failSends) {
                CompletableFuture<WebSocket> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("send failed"));
                return failed;
            }
            sentTexts.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
