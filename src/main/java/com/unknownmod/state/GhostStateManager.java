package com.unknownmod.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GhostStateManager extends PersistentState {
    private final Set<UUID> ghosts;

    public static final Codec<GhostStateManager> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.listOf().fieldOf("ghosts").forGetter(state ->
                            state.ghosts.stream().map(UUID::toString).toList()
                    )
            ).apply(instance, uuidStrings -> {
                Set<UUID> set = new HashSet<>();
                for (String s : uuidStrings) {
                    try {
                        set.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {}
                }
                return new GhostStateManager(set);
            })
    );

    public static final PersistentStateType<GhostStateManager> TYPE = new PersistentStateType<>(
            "unknownmod_ghosts",
            () -> new GhostStateManager(new HashSet<>()),
            CODEC,
            null
    );

    private GhostStateManager(Set<UUID> ghosts) {
        this.ghosts = ghosts;
    }

    public static GhostStateManager getServerState(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public void addGhost(UUID uuid) {
        ghosts.add(uuid);
        markDirty();
    }

    public boolean isGhost(UUID uuid) {
        return ghosts.contains(uuid);
    }
}
