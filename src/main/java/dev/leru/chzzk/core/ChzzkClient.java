package dev.leru.chzzk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

public final class ChzzkClient implements AutoCloseable {
    private static final String SOURCE_TEMPLATE = "https://api.chzzk.naver.com/manage/v1/chats/sources/%s";
    private static final String TOKEN_TEMPLATE = "https://comm-api.game.naver.com/nng_main/v1/chats/access-token?channelId=%s&chatType=STREAMING";
    private static final List<URI> WS_URIS = List.of(
            URI.create("wss://kr-ss1.chat.naver.com/chat"),
            URI.create("wss://kr-ss2.chat.naver.com/chat"),
            URI.create("wss://kr-ss3.chat.naver.com/chat"),
            URI.create("wss://kr-ss4.chat.naver.com/chat")
    );

    private final String sourceHash;
    private final Consumer<RawDonation> onDonation;
    private final Consumer<String> onStatus;
    private final HttpClient httpClient;
    private final EndpointResolver endpointResolver;
    private final WebSocketConnector webSocketConnector;
    private final boolean ownsHttpClient;
    private final ExecutorService ownedHttpExecutor;
    private final ExecutorService runner;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService callbackExecutor;
    private final ArrayBlockingQueue<RawDonation> callbackQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger wsIndex = new AtomicInteger();
    private final AtomicLong lastFrameMillis = new AtomicLong();
    private final Random random = new Random();
    private volatile WebSocket webSocket;
    private volatile ChatListener activeListener;
    private volatile ScheduledFuture<?> heartbeatFuture;

    public ChzzkClient(String sourceHash, Consumer<RawDonation> onDonation, Consumer<String> onStatus) {
        this(sourceHash, onDonation, onStatus, newBoundedHttpExecutor(), true, new DirectEndpointResolver(), null);
    }

    ChzzkClient(String sourceHash, Consumer<RawDonation> onDonation, Consumer<String> onStatus,
                HttpClient httpClient, EndpointResolver endpointResolver, WebSocketConnector connector) {
        this(sourceHash, onDonation, onStatus, httpClient, false, null, endpointResolver, connector);
    }

    private ChzzkClient(String sourceHash, Consumer<RawDonation> onDonation, Consumer<String> onStatus,
                       ExecutorService ownedHttpExecutor, boolean ownsHttpClient,
                       EndpointResolver endpointResolver, WebSocketConnector connector) {
        this(sourceHash, onDonation, onStatus, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .executor(ownedHttpExecutor)
                .build(), ownsHttpClient, ownedHttpExecutor, endpointResolver, connector);
    }

    private ChzzkClient(String sourceHash, Consumer<RawDonation> onDonation, Consumer<String> onStatus,
                       HttpClient httpClient, boolean ownsHttpClient, ExecutorService ownedHttpExecutor,
                       EndpointResolver endpointResolver, WebSocketConnector connector) {
        if (!DonationParser.isValidSourceHash(sourceHash)) {
            throw new IllegalArgumentException("invalid sourceHash");
        }
        this.sourceHash = sourceHash;
        this.onDonation = Objects.requireNonNull(onDonation, "onDonation");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.ownsHttpClient = ownsHttpClient;
        this.ownedHttpExecutor = ownedHttpExecutor;
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
        this.webSocketConnector = connector == null ? new HttpClientWebSocketConnector(httpClient) : connector;
        this.runner = Executors.newSingleThreadExecutor(r -> daemon(r, "chzzk-client"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "chzzk-client-timer"));
        this.callbackQueue = new ArrayBlockingQueue<>(256);
        this.callbackExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "chzzk-callback"));
        this.callbackExecutor.execute(this::drainCallbacks);
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("client closed");
        }
        if (running.compareAndSet(false, true)) {
            runner.execute(this::runLoop);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        running.set(false);
        cancelHeartbeat();
        ChatListener listener = activeListener;
        WebSocket socket = webSocket;
        if (listener != null) {
            listener.release();
        }
        abortSocket(socket);
        if (ownsHttpClient) {
            httpClient.shutdownNow();
        }
        if (ownedHttpExecutor != null) {
            ownedHttpExecutor.shutdownNow();
        }
        runner.shutdownNow();
        scheduler.shutdownNow();
        callbackExecutor.shutdownNow();
    }

    private void runLoop() {
        int attempt = 0;
        while (running.get() && !closed.get()) {
            try {
                status("resolving");
                ResolvedChat resolved = endpointResolver.resolve(sourceHash, httpClient);
                status("connecting");
                URI wsUri = WS_URIS.get(Math.floorMod(wsIndex.getAndIncrement(), WS_URIS.size()));
                ChatListener listener = new ChatListener(resolved.chatChannelId());
                WebSocket socket = webSocketConnector.connect(wsUri, listener, Duration.ofSeconds(8));
                if (!running.get() || closed.get()) {
                    listener.release();
                    abortSocket(socket);
                    break;
                }
                webSocket = socket;
                activeListener = listener;
                sendTextOrClose(socket, listener, joinFrame(resolved.chatChannelId(), resolved.accessToken(), listener.nextTid()), Duration.ofSeconds(8));
                lastFrameMillis.set(System.currentTimeMillis());
                if (!listener.awaitAck(Duration.ofSeconds(8))) {
                    listener.release();
                    abortSocket(socket);
                    throw new IOException("handshake failed");
                }
                status("connected");
                attempt = 0;
                startHeartbeat(resolved.chatChannelId(), listener);
                listener.awaitClosed();
                cancelHeartbeat();
                activeListener = null;
                webSocket = null;
                if (running.get() && !closed.get()) {
                    throw new IOException("connection closed");
                }
            } catch (Exception ex) {
                cancelHeartbeat();
                ChatListener listener = activeListener;
                WebSocket socket = webSocket;
                activeListener = null;
                webSocket = null;
                if (listener != null) {
                    listener.release();
                }
                abortSocket(socket);
                if (!running.get() || closed.get()) {
                    break;
                }
                status("reconnecting");
                sleepBackoff(attempt++);
            }
        }
        status("stopped");
    }

    private void startHeartbeat(String chatChannelId, ChatListener listener) {
        cancelHeartbeat();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!running.get() || closed.get()) {
                return;
            }
            WebSocket socket = webSocket;
            if (socket == null) {
                return;
            }
            long idleMillis = System.currentTimeMillis() - lastFrameMillis.get();
            if (idleMillis > 60_000L) {
                listener.release();
                abortSocket(socket);
                return;
            }
            try {
                sendTextOrClose(socket, listener, pingFrame(chatChannelId, listener.nextTid()), Duration.ofSeconds(8));
            } catch (Exception ignored) {
                listener.release();
                abortSocket(socket);
            }
        }, 25, 25, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> future = heartbeatFuture;
        if (future != null) {
            future.cancel(true);
        }
        heartbeatFuture = null;
    }

    private void sendTextOrClose(WebSocket socket, ChatListener listener, String text, Duration timeout) throws Exception {
        CompletableFuture<WebSocket> send = socket.sendText(text, true);
        try {
            send.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            send.cancel(true);
            listener.release();
            abortSocket(socket);
            throw ex;
        } catch (Exception ex) {
            listener.release();
            abortSocket(socket);
            throw ex;
        }
    }

    private static void abortSocket(WebSocket socket) {
        if (socket != null) {
            try {
                socket.abort();
            } catch (RuntimeException ignored) {
                // close must not leak implementation details.
            }
        }
    }

    private static ExecutorService newBoundedHttpExecutor() {
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        return Executors.newFixedThreadPool(threads, r -> daemon(r, "chzzk-http"));
    }

    private void sleepBackoff(int attempt) {
        long base = Math.min(30_000L, 1_000L << Math.min(attempt, 5));
        long jitter = random.nextLong(Math.max(1L, base / 2L));
        try {
            Thread.sleep(base / 2L + jitter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void offerDonation(RawDonation donation) {
        if (!callbackQueue.offer(donation)) {
            status("callback queue full");
        }
    }

    private void drainCallbacks() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try {
                RawDonation donation = callbackQueue.poll(250, TimeUnit.MILLISECONDS);
                if (donation != null && running.get()) {
                    onDonation.accept(donation);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                status("callback failed");
            }
        }
    }

    private void status(String value) {
        try {
            onStatus.accept(value);
        } catch (RuntimeException ignored) {
            // Status callbacks are advisory.
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String joinFrame(String channelId, String accessToken, int tid) {
        JsonObject root = new JsonObject();
        root.addProperty("ver", "2");
        root.addProperty("cmd", 100);
        root.addProperty("svcid", "game");
        root.addProperty("cid", channelId);
        JsonObject body = new JsonObject();
        body.add("uid", null);
        body.addProperty("devType", 2001);
        body.addProperty("accTkn", accessToken);
        body.addProperty("auth", "READ");
        body.addProperty("recentMessageCount", 0);
        root.add("bdy", body);
        root.addProperty("tid", tid);
        return DonationParser.toJson(root);
    }

    private static String pingFrame(String channelId, int tid) {
        JsonObject root = new JsonObject();
        root.addProperty("ver", "2");
        root.addProperty("cmd", 0);
        root.addProperty("svcid", "game");
        root.addProperty("cid", channelId);
        root.add("bdy", new JsonObject());
        root.addProperty("tid", tid);
        return DonationParser.toJson(root);
    }

    private static String pongFrame(String channelId, int tid) {
        JsonObject root = new JsonObject();
        root.addProperty("ver", "2");
        root.addProperty("cmd", 10000);
        root.addProperty("svcid", "game");
        root.addProperty("cid", channelId);
        root.add("bdy", new JsonObject());
        root.addProperty("tid", tid);
        return DonationParser.toJson(root);
    }

    record ResolvedChat(String chatChannelId, String accessToken) {}

    interface EndpointResolver {
        ResolvedChat resolve(String sourceHash, HttpClient httpClient) throws IOException, InterruptedException;
    }

    interface WebSocketConnector {
        WebSocket connect(URI uri, WebSocket.Listener listener, Duration timeout) throws Exception;
    }

    static EndpointResolver directEndpointResolverForTest(String sourceTemplate, String tokenTemplate, Duration timeout) {
        return new DirectEndpointResolver(sourceTemplate, tokenTemplate, timeout);
    }

    private static final class DirectEndpointResolver implements EndpointResolver {
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

        private final String sourceTemplate;
        private final String tokenTemplate;
        private final Duration requestTimeout;

        private DirectEndpointResolver() {
            this(SOURCE_TEMPLATE, TOKEN_TEMPLATE, REQUEST_TIMEOUT);
        }

        private DirectEndpointResolver(String sourceTemplate, String tokenTemplate, Duration requestTimeout) {
            this.sourceTemplate = Objects.requireNonNull(sourceTemplate, "sourceTemplate");
            this.tokenTemplate = Objects.requireNonNull(tokenTemplate, "tokenTemplate");
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        }

        @Override
        public ResolvedChat resolve(String sourceHash, HttpClient httpClient) throws IOException, InterruptedException {
            String sourceUrl = sourceTemplate.formatted(sourceHash);
            JsonObject source = getJson(httpClient, URI.create(sourceUrl), requestTimeout);
            JsonObject content = object(source.get("content"));
            String channelId = string(content.get("chatChannelId"));
            if (channelId == null || channelId.isBlank()) {
                throw new IOException("source resolution failed");
            }
            String tokenUrl = tokenTemplate.formatted(URLEncoder.encode(channelId, StandardCharsets.UTF_8));
            JsonObject token = getJson(httpClient, URI.create(tokenUrl), requestTimeout);
            String accessToken = string(object(token.get("content")).get("accessToken"));
            if (accessToken == null || accessToken.isBlank()) {
                throw new IOException("token resolution failed");
            }
            return new ResolvedChat(channelId.trim(), accessToken.trim());
        }

        private static JsonObject getJson(HttpClient httpClient, URI uri, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .GET()
                    .header("accept", "application/json")
                    .build();
            CompletableFuture<HttpResponse<String>> pending = httpClient.sendAsync(request,
                    responseInfo -> new LimitedUtf8BodySubscriber(DonationParser.MAX_FRAME_BYTES));
            HttpResponse<String> response;
            try {
                response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                pending.cancel(true);
                throw new IOException("upstream request failed");
            } catch (InterruptedException ex) {
                pending.cancel(true);
                throw ex;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof IOException io && "upstream response too large".equals(io.getMessage())) {
                    throw io;
                }
                throw new IOException("upstream request failed");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("upstream request failed");
            }
            try {
                JsonElement parsed = JsonParser.parseString(response.body());
                return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (JsonParseException | IllegalStateException ex) {
                throw new IOException("upstream response invalid");
            }
        }

        private static final class LimitedUtf8BodySubscriber implements HttpResponse.BodySubscriber<String> {
            private final int maxBytes;
            private final CompletableFuture<String> body = new CompletableFuture<>();
            private final AtomicBoolean done = new AtomicBoolean(false);
            private final ByteArrayOutputStream out;
            private volatile Flow.Subscription subscription;

            private LimitedUtf8BodySubscriber(int maxBytes) {
                this.maxBytes = maxBytes;
                this.out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            }

            @Override
            public CompletionStage<String> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                if (done.get()) {
                    subscription.cancel();
                } else {
                    subscription.request(1);
                }
            }

            @Override
            public void onNext(List<ByteBuffer> items) {
                if (done.get()) {
                    return;
                }
                try {
                    for (ByteBuffer item : items) {
                        ByteBuffer buffer = item.slice();
                        int remaining = buffer.remaining();
                        if (out.size() + remaining > maxBytes) {
                            fail(new IOException("upstream response too large"));
                            return;
                        }
                        byte[] bytes = new byte[remaining];
                        buffer.get(bytes);
                        out.writeBytes(bytes);
                    }
                } catch (RuntimeException ex) {
                    fail(new IOException("upstream request failed"));
                    return;
                }
                Flow.Subscription current = subscription;
                if (current != null && !done.get()) {
                    current.request(1);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                fail(throwable);
            }

            @Override
            public void onComplete() {
                if (done.compareAndSet(false, true)) {
                    body.complete(out.toString(StandardCharsets.UTF_8));
                }
            }

            private void fail(Throwable throwable) {
                if (done.compareAndSet(false, true)) {
                    Flow.Subscription current = subscription;
                    if (current != null) {
                        current.cancel();
                    }
                    body.completeExceptionally(throwable);
                }
            }
        }
    }

    private static final class HttpClientWebSocketConnector implements WebSocketConnector {
        private final HttpClient httpClient;

        private HttpClientWebSocketConnector(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public WebSocket connect(URI uri, WebSocket.Listener listener, Duration timeout) throws Exception {
            AtomicBoolean timedOut = new AtomicBoolean(false);
            CompletableFuture<WebSocket> pending = httpClient.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(uri, listener);
            pending.whenComplete((socket, error) -> {
                if (timedOut.get() && socket != null) {
                    abortSocket(socket);
                }
            });
            try {
                return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                timedOut.set(true);
                pending.cancel(true);
                throw ex;
            }
        }
    }

    private final class ChatListener implements WebSocket.Listener {
        private final String channelId;
        private final AtomicInteger tid = new AtomicInteger(1);
        private final StringBuilder partial = new StringBuilder();
        private final CountDownLatch ack = new CountDownLatch(1);
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final AtomicBoolean joined = new AtomicBoolean(false);
        private final AtomicBoolean failed = new AtomicBoolean(false);

        private ChatListener(String channelId) {
            this.channelId = channelId;
        }

        int nextTid() {
            return tid.getAndIncrement();
        }

        boolean awaitAck(Duration timeout) throws InterruptedException {
            return ack.await(timeout.toMillis(), TimeUnit.MILLISECONDS) && !failed.get();
        }

        void awaitClosed() throws InterruptedException {
            closedLatch.await();
        }

        void release() {
            failed.set(true);
            ack.countDown();
            closedLatch.countDown();
        }

        private boolean isActive() {
            return activeListener == this && running.get() && !closed.get() && !failed.get();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!isActive()) {
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
            if (partial.length() + data.length() > DonationParser.MAX_FRAME_BYTES) {
                partial.setLength(0);
                release();
                abortSocket(webSocket);
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                handleFrame(webSocket, frame);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            if (!isActive()) {
                return WebSocket.Listener.super.onBinary(webSocket, data, last);
            }
            if (data.remaining() > DonationParser.MAX_FRAME_BYTES) {
                release();
                abortSocket(webSocket);
            }
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
            if (!isActive()) {
                return WebSocket.Listener.super.onPing(webSocket, message);
            }
            lastFrameMillis.set(System.currentTimeMillis());
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
            if (!isActive()) {
                return WebSocket.Listener.super.onPong(webSocket, message);
            }
            lastFrameMillis.set(System.currentTimeMillis());
            return WebSocket.Listener.super.onPong(webSocket, message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            release();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            release();
        }

        private void handleFrame(WebSocket socket, String frame) {
            if (!isActive()) {
                return;
            }
            lastFrameMillis.set(System.currentTimeMillis());
            Integer cmd = cmd(frame);
            if (cmd != null) {
                if (cmd == 10100 && joinSucceeded(frame)) {
                    joined.set(true);
                    ack.countDown();
                    return;
                }
                if (cmd == 0) {
                    try {
                        sendTextOrClose(socket, this, pongFrame(channelId, nextTid()), Duration.ofSeconds(8));
                    } catch (Exception ignored) {
                        release();
                        abortSocket(socket);
                    }
                    return;
                }
            }
            if (!joined.get()) {
                return;
            }
            for (RawDonation donation : DonationParser.parse(sourceHash, frame)) {
                if (isActive()) {
                    offerDonation(donation);
                }
            }
        }

        private boolean joinSucceeded(String frame) {
            try {
                JsonElement parsed = JsonParser.parseString(frame);
                if (!parsed.isJsonObject()) {
                    return false;
                }
                JsonObject object = parsed.getAsJsonObject();
                JsonElement retCode = object.get("retCode");
                if (retCode == null) {
                    retCode = object.get("retcode");
                }
                if (retCode == null) {
                    JsonElement body = object.get("bdy");
                    if (body != null && body.isJsonObject()) {
                        JsonObject bodyObject = body.getAsJsonObject();
                        retCode = bodyObject.has("retCode") ? bodyObject.get("retCode") : bodyObject.get("retcode");
                    }
                }
                return retCode == null || (retCode.isJsonPrimitive() && !retCode.getAsJsonPrimitive().isBoolean() && retCode.getAsInt() == 0);
            } catch (RuntimeException ex) {
                return false;
            }
        }

        private Integer cmd(String frame) {
            try {
                JsonElement parsed = JsonParser.parseString(frame);
                if (!parsed.isJsonObject()) {
                    return null;
                }
                JsonElement cmd = parsed.getAsJsonObject().get("cmd");
                if (cmd == null || !cmd.isJsonPrimitive() || cmd.getAsJsonPrimitive().isBoolean()) {
                    return null;
                }
                return cmd.getAsInt();
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
