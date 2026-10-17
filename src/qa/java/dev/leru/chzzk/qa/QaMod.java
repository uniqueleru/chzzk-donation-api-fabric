package dev.leru.chzzk.qa;

import dev.leru.chzzk.api.ChzzkDonationEvents;
import dev.leru.chzzk.fabric.PipelineProbe;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Isolated runtime probe; this source set is NOT shipped. */
public final class QaMod implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("chzzk-donation-qa");
    public static volatile boolean passed;
    private int testEvents;
    private int realEvents;
    private int ticks;
    private boolean joined;
    private String joinedName;
    private PipelineProbe pipeline;
    private boolean pipelinePassed;
    @Override public void onInitialize() {
        ChzzkDonationEvents.RECEIVED.register((server, event) -> {
            if (!server.isOnThread() || event.test() || !event.bindingId().equals("qa-fixture")) throw new AssertionError("Unexpected real-path event");
            realEvents++;
        });
        ChzzkDonationEvents.TEST.register((server, event) -> { throw new IllegalStateException("intentional-QA-listener-error"); });
        ChzzkDonationEvents.TEST.register((server, event) -> {
            if (!server.isOnThread() || !event.test() || event.amount() != 1234L)
                throw new AssertionError("QA event contract failed");
            testEvents++;
            LOG.info("QA_TEST_CALLBACK thread=server amount=1234 test=true");
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            passed = false; pipelinePassed = false; ticks = 0; joined = false; testEvents = 0; realEvents = 0;
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), "chzzkdonation status");
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), "chzzkdonation reload");
            LOG.info("QA_SERVER_STARTED dedicated={}", server.isDedicated());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            joined = true; ticks = 0;
            joinedName = handler.player.getName().getString();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (joined && !passed && ++ticks == 5) {
                server.getCommandManager().executeWithPrefix(server.getCommandSource(), "chzzkdonation test " + joinedName + " 1234 QA-only");
                if (testEvents != 1 || realEvents != 0) throw new AssertionError("TEST leaked to real listeners");
                try {
                    pipeline = new PipelineProbe(server, server.getPlayerManager().getPlayer(joinedName).getUuid());
                } catch (Exception e) { throw new AssertionError("Pipeline init failed", e); }
            }
            if (pipeline != null && !pipelinePassed) pipelinePassed = pipeline.tick();
            if (joined && !passed && ticks >= 30) {
                if (testEvents != 1 || realEvents != 1 || !pipelinePassed) throw new AssertionError("QA pipeline counts fixture=" + realEvents + " test=" + testEvents);
                pipeline.close(); pipeline = null;
                passed = true;
                LOG.info("QA_PASS dedicated={} testEvents={} fixtureReceivedEvents={} listenerIsolation=true dedup=true offlineDrop=true rejoinDrop=true paidDonation=false", server.isDedicated(), testEvents, realEvents);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (pipeline != null) { pipeline.close(); pipeline = null; }
            LOG.info("QA_SERVER_STOPPED passed={}", passed);
        });
    }
}
