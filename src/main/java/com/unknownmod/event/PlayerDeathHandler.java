package com.unknownmod.event;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.world.GameMode;
import com.unknownmod.state.GhostStateManager;
import com.unknownmod.state.RevelationManager;

public class PlayerDeathHandler {
    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(
                (ServerWorld world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) -> {
                    if (entity instanceof ServerPlayerEntity killer && killedEntity instanceof ServerPlayerEntity victim) {
                        ItemStack weapon = killer.getMainHandStack();

                        if (weapon.contains(DataComponentTypes.CUSTOM_NAME)) {
                            String weaponName = weapon.getName().getString();
                            String victimName = victim.getName().getString();

                            if (weaponName.equalsIgnoreCase(victimName)) {
                                GhostStateManager state = GhostStateManager.getServerState(world.getServer());
                                state.addGhost(victim.getUuid());
                                victim.changeGameMode(GameMode.SPECTATOR);
                                RevelationManager.eliminateReveal(world.getServer(), victim, killer.getName().getString());
                            }
                        }
                    }
                });
    }
}
