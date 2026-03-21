package com.unknownmod.event;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.GameMode;
import com.unknownmod.state.GhostStateManager;

public class PlayerDeathHandler {
    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((entity, killedEntity) -> {
            if (entity instanceof ServerPlayerEntity killer && killedEntity instanceof ServerPlayerEntity victim) {
                ItemStack weapon = killer.getMainHandStack();
                
                if (weapon.hasCustomName()) {
                    String weaponName = weapon.getName().getString();
                    String victimName = victim.getGameProfile().getName();

                    if (weaponName.equalsIgnoreCase(victimName)) {
                        GhostStateManager state = GhostStateManager.getServerState(killer.getServer());
                        state.addGhost(victim.getUuid());
                        victim.changeGameMode(GameMode.SPECTATOR);
                    }
                }
            }
        });
    }
}
