package com.unknownmod.state;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentState;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GhostStateManager extends PersistentState {
    private final Set<UUID> ghosts = new HashSet<>();

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        NbtList list = new NbtList();
        for (UUID uuid : ghosts) {
            list.add(NbtString.of(uuid.toString()));
        }
        nbt.put("Ghosts", list);
        return nbt;
    }

    public static GhostStateManager createFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        GhostStateManager state = new GhostStateManager();
        NbtList list = nbt.getList("Ghosts", NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) {
            state.ghosts.add(UUID.fromString(list.getString(i)));
        }
        return state;
    }

    public static GhostStateManager getServerState(MinecraftServer server) {
        net.minecraft.server.world.ServerWorld world = server.getOverworld();
        net.minecraft.world.PersistentStateManager persistentStateManager = world.getPersistentStateManager();

        Type<GhostStateManager> type = new Type<>(
                GhostStateManager::new,
                GhostStateManager::createFromNbt,
                null
        );

        GhostStateManager state = persistentStateManager.getOrCreate(type, "unknownmod_ghosts");
        state.markDirty();
        return state;
    }

    public void addGhost(UUID uuid) {
        ghosts.add(uuid);
        markDirty();
    }

    public boolean isGhost(UUID uuid) {
        return ghosts.contains(uuid);
    }
}
