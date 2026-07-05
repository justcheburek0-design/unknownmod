package com.unknownmod.mixin;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.mixin.server.ServerGamePacketListenerAccessor;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.util.ProfileApplier;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hide anonymous (non-revealed) players from the tab list.
 *
 * Sets listed=false on entries for non-revealed players in every
 * ClientboundPlayerInfoUpdatePacket. For ADD_PLAYER / UPDATE_LISTED
 * actions this hides them from tab; for other action types the client
 * ignores the listed field. Revealed players keep their listed status.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class TabListFilterMixin {

    @Shadow
    @Final
    protected MinecraftServer server;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void unknownmod$filterTabList(Packet<?> packet, CallbackInfo ci) {
        handleOutgoingPacket(packet, null, ci);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void unknownmod$filterTabListWithListener(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        handleOutgoingPacket(packet, listener, ci);
    }

    private void handleOutgoingPacket(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        UUID viewerUuid = getViewerUuid();
        Packet<?> filteredPacket = filterPacket(packet, viewerUuid);

        if (filteredPacket != packet) {
            if (listener != null) {
                ((ServerCommonPacketListenerImpl) (Object) this).send(filteredPacket, listener);
            } else {
                ((ServerCommonPacketListenerImpl) (Object) this).send(filteredPacket);
            }
            ci.cancel();
        }
    }

    private Packet<?> filterPacket(Packet<?> packet, UUID viewerUuid) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket playerInfoPacket) {
            ProfileApplier.anonymizePlayerInfoPacket(server, playerInfoPacket, ConfigManager.getConfig(), viewerUuid);
            hideNonRevealed(playerInfoPacket);
            return packet;
        }

        if (packet instanceof ClientboundCommandSuggestionsPacket suggestionsPacket) {
            return anonymizeCommandSuggestions(suggestionsPacket);
        }

        if (packet instanceof ClientboundBundlePacket bundlePacket) {
            return filterBundlePacket(bundlePacket, viewerUuid);
        }

        return packet;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Packet<?> filterBundlePacket(ClientboundBundlePacket bundlePacket, UUID viewerUuid) {
        List<Packet<? super ClientGamePacketListener>> filteredSubPackets = new ArrayList<>();
        boolean changed = false;

        for (Packet<? super ClientGamePacketListener> subPacket : bundlePacket.subPackets()) {
            Packet<?> filteredSubPacket = filterPacket((Packet<?>) subPacket, viewerUuid);
            if (filteredSubPacket != subPacket) {
                changed = true;
            }
            filteredSubPackets.add((Packet<? super ClientGamePacketListener>) filteredSubPacket);
        }

        return changed ? new ClientboundBundlePacket(filteredSubPackets) : bundlePacket;
    }

    private UUID getViewerUuid() {
        if (!((Object) this instanceof ServerGamePacketListenerImpl)) {
            return null;
        }

        ServerPlayer player = ((ServerGamePacketListenerAccessor) (Object) this).unknownmod$getPlayer();
        return player == null ? null : player.getUUID();
    }

    private void hideNonRevealed(ClientboundPlayerInfoUpdatePacket packet) {
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = packet.entries();
        if (entries.isEmpty()) {
            return;
        }

        List<ClientboundPlayerInfoUpdatePacket.Entry> patched = new ArrayList<>(entries.size());
        boolean changed = false;

        for (ClientboundPlayerInfoUpdatePacket.Entry entry : entries) {
            // Keep revealed players visible in tab
            if (server != null && RevelationManager.isRevealed(server, entry.profileId())) {
                patched.add(entry);
                continue;
            }

            // Already unlisted — no change needed
            if (!entry.listed()) {
                patched.add(entry);
                continue;
            }

            // Hide non-revealed player from tab
            patched.add(new ClientboundPlayerInfoUpdatePacket.Entry(
                    entry.profileId(),
                    entry.profile(),
                    false,
                    entry.latency(),
                    entry.gameMode(),
                    entry.displayName(),
                    entry.showHat(),
                    entry.listOrder(),
                    entry.chatSession()
            ));
            changed = true;
        }

        if (changed) {
            ((com.unknownmod.mixin.PlayerListS2CPacketAccessor) packet).setEntries(patched);
        }
    }

    private ClientboundCommandSuggestionsPacket anonymizeCommandSuggestions(ClientboundCommandSuggestionsPacket packet) {
        if (server == null) {
            return packet;
        }

        UnknownConfig config = ConfigManager.getConfig();
        if (config.anonymous == null || config.anonymous.name == null || config.anonymous.name.isBlank()) {
            return packet;
        }

        String anonymousName = config.anonymous.name;
        List<ClientboundCommandSuggestionsPacket.Entry> entries = packet.suggestions();
        if (entries.isEmpty()) {
            return packet;
        }

        List<ClientboundCommandSuggestionsPacket.Entry> filtered = new ArrayList<>(entries.size());
        Set<String> emitted = new HashSet<>();
        boolean changed = false;

        for (ClientboundCommandSuggestionsPacket.Entry entry : entries) {
            String text = entry.text();
            String replacement = anonymizePlayerSuggestion(text, anonymousName);
            if (!replacement.equals(text)) {
                changed = true;
            }

            // Multiple hidden players all become the same public nickname. Emit it once.
            String dedupeKey = replacement.toLowerCase(java.util.Locale.ROOT);
            if (replacement.equals(anonymousName) && !emitted.add(dedupeKey)) {
                changed = true;
                continue;
            }

            if (changed || !filtered.isEmpty()) {
                filtered.add(new ClientboundCommandSuggestionsPacket.Entry(
                        replacement,
                        entry.tooltip()
                ));
            } else {
                filtered.add(entry);
            }
        }

        if (changed) {
            return new ClientboundCommandSuggestionsPacket(packet.id(), packet.start(), packet.length(), filtered);
        }
        return packet;
    }

    private String anonymizePlayerSuggestion(String text, String anonymousName) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // Check if this suggestion is a real player name that should be hidden
        if (server == null) {
            return text;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equals(text) && !RevelationManager.isRevealed(server, player.getUUID())) {
                return anonymousName;
            }
        }
        return text;
    }
}
