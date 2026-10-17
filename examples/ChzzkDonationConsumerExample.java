package example;

import dev.leru.chzzk.api.ChzzkDonationEvents;
import net.fabricmc.api.ModInitializer;
import net.minecraft.text.Text;

/**
 * Minimal consumer example for real donations.
 *
 * <p>Register TEST separately if you want operator test commands to exercise your code. Do not give
 * in-game rewards from TEST unless your own mod deliberately opts in.</p>
 */
public final class ChzzkDonationConsumerExample implements ModInitializer {
    @Override
    public void onInitialize() {
        ChzzkDonationEvents.RECEIVED.register((server, donation) -> {
            server.getPlayerManager().getPlayerList().forEach(player -> player.sendMessage(Text.literal(
                    donation.donor() + " donated " + donation.amount() + ": " + donation.message()
            )));
        });

        ChzzkDonationEvents.TEST.register((server, donation) -> server.getPlayerManager().getPlayerList().forEach(player ->
                player.sendMessage(Text.literal("[TEST] " + donation.amount() + ": " + donation.message()))
        ));
    }
}
