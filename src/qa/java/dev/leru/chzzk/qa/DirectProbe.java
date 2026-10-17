package dev.leru.chzzk.qa;

import dev.leru.chzzk.core.ChzzkClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Plain-Java read-only upstream handshake; never executes game effects or logs source. */
public final class DirectProbe {
    public static void main(String[] args) throws Exception {
        String source = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        CountDownLatch connected = new CountDownLatch(1);
        try (ChzzkClient client = new ChzzkClient(source, ignored -> {}, state -> {
            if (state.equals("connected")) connected.countDown();
            System.out.println("state=" + state);
        })) {
            client.start();
            if (!connected.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Direct Java handshake timed out");
            System.out.println("JAVA_DIRECT_HANDSHAKE_PASS paid_donation_verified=false");
        }
        Thread.sleep(500);
        long alive = Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith("chzzk-")).count();
        System.out.println("owned_threads_after_close=" + alive);
        if (alive != 0) throw new IllegalStateException("CHZZK threads remain after close");
    }
}
