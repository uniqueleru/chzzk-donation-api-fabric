package dev.leru.chzzk.fabric;

import dev.leru.chzzk.core.RawDonation;
import dev.leru.chzzk.core.SeenEventStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/** QA-only fixture: synthetic network input exercises RECEIVED, not a real payment. */
public final class PipelineProbe implements AutoCloseable {
    private final ChzzkDonationService service;
    private final ChzzkDonationConfig.Binding binding;
    private final String unique = UUID.randomUUID().toString();
    private int stage;

    public PipelineProbe(MinecraftServer server, UUID player) throws Exception {
        Path root = server.getSavePath(WorldSavePath.ROOT).resolve("qa-only-pipeline");
        binding = new ChzzkDonationConfig.Binding("qa-fixture", player, "0123456789abcdef0123456789abcdef", true);
        var worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "qa-pipeline-io"); t.setDaemon(true); return t; });
        service = new ChzzkDonationService(server, root.resolve("unused-config.json"), root.resolve("seen.tsv"), worker);
        service.startQaReceivePath(new SeenEventStore(root.resolve("seen.tsv")), new ChzzkDonationConfig(1, List.of(binding)), Map.of(player, 1L));
        RawDonation raw = new RawDonation("qa-fixture-" + unique, "QA synthetic", "NOT A REAL PAYMENT", 4321L, System.currentTimeMillis());
        service.receiveQaDonation(binding, raw);
        service.receiveQaDonation(binding, raw);
    }

    public boolean tick() {
        service.tickQaDispatchWithoutRefreshingPlayers();
        String status = service.statusText();
        if (stage == 0 && status.contains("dispatched=1") && status.contains("duplicate=1")) {
            service.setQaOnlineEpochs(Map.of());
            service.receiveQaDonation(binding, new RawDonation("offline-" + unique, "QA", "offline", 4321L, 1));
            service.setQaOnlineEpochs(Map.of(binding.playerUuid(), 1L));
            service.receiveQaDonation(binding, new RawDonation("rejoin-" + unique, "QA", "rejoin", 4321L, 1));
            service.setQaOnlineEpochs(Map.of(binding.playerUuid(), 2L));
            stage = 1;
        }
        return stage == 1 && status.contains("dispatched=1") && status.contains("duplicate=1") && status.contains("offlineDropped=2");
    }

    @Override public void close() { service.close(); }
}
