package dev.leru.chzzk.fabric;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChzzkDonationMod implements ModInitializer {
    public static final String MOD_ID = "chzzk_donation_api";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile ChzzkDonationService service;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::serverStopping);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ChzzkDonationService current = service;
            if (current != null) {
                current.tick();
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(commandTree()));
    }

    private void serverStarted(MinecraftServer server) {
        ChzzkDonationService next = new ChzzkDonationService(
                server,
                FabricLoader.getInstance().getConfigDir().resolve("chzzk-donation-api.json")
        );
        service = next;
        next.start();
        LOGGER.info("CHZZK Donation API initialized for this server session");
    }

    private void serverStopping(MinecraftServer server) {
        ChzzkDonationService current = service;
        service = null;
        if (current != null) {
            current.close();
        }
    }

    private LiteralArgumentBuilder<ServerCommandSource> commandTree() {
        return CommandManager.literal("chzzkdonation")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(CommandManager.literal("reload")
                        .executes(context -> reload(context.getSource())))
                .then(CommandManager.literal("test")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("amount", LongArgumentType.longArg(1))
                                        .executes(context -> test(
                                                context.getSource(),
                                                EntityArgumentType.getPlayer(context, "player"),
                                                LongArgumentType.getLong(context, "amount"),
                                                "CHZZK donation test"
                                        ))
                                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> test(
                                                        context.getSource(),
                                                        EntityArgumentType.getPlayer(context, "player"),
                                                        LongArgumentType.getLong(context, "amount"),
                                                        StringArgumentType.getString(context, "message")
                                                ))))));
    }

    private int status(ServerCommandSource source) {
        ChzzkDonationService current = service;
        String text = current == null ? "CHZZK Donation API: stopped" : current.statusText();
        source.sendFeedback(() -> Text.literal(text), false);
        return 1;
    }

    private int reload(ServerCommandSource source) {
        ChzzkDonationService current = service;
        if (current == null) {
            source.sendError(Text.literal("CHZZK Donation API is not running"));
            return 0;
        }
        if (current.reload()) {
            source.sendFeedback(() -> Text.literal("CHZZK Donation API config reloaded"), true);
            return 1;
        }
        source.sendError(Text.literal("CHZZK Donation API config is invalid; previous configuration retained"));
        return 0;
    }

    private int test(ServerCommandSource source, ServerPlayerEntity player, long amount, String message) {
        ChzzkDonationService current = service;
        if (current == null) {
            source.sendError(Text.literal("CHZZK Donation API is not running"));
            return 0;
        }
        current.dispatchTest(player, amount, message);
        source.sendFeedback(() -> Text.literal("Sent synthetic CHZZK donation TEST event to " + player.getName().getString()), true);
        return 1;
    }
}
