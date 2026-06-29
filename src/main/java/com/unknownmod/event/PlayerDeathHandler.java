package com.unknownmod.event;

import com.unknownmod.state.GhostStateManager;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.ServerContextHolder;
import com.unknownmod.util.DebugMessenger;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDeathHandler {
    private static final long PLAYER_PARTICIPATION_WINDOW_MS = 15_000L;

    private static final Set<UUID> processedDeaths = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, PlayerParticipation> recentPlayerParticipation = new ConcurrentHashMap<>();

    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(PlayerDeathHandler::handleDirectKill);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(PlayerDeathHandler::trackDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(PlayerDeathHandler::handleDeath);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processedDeaths.clear();
            pruneParticipation(System.currentTimeMillis());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            processedDeaths.clear();
            recentPlayerParticipation.clear();
        });
    }

    private static void handleDirectKill(ServerLevel world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) {
        if (!(entity instanceof ServerPlayer killer) || !(killedEntity instanceof ServerPlayer victim)) {
            return;
        }

        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }

        processDeath(server, victim, killer, damageSource);
    }

    private static void trackDamage(LivingEntity entity, DamageSource damageSource, float dealtDamage, float takenDamage, boolean blocked) {
        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }

        ServerPlayer player = resolvePlayerParticipant(damageSource);
        if (player == null) {
            return;
        }

        recentPlayerParticipation.put(victim.getUUID(), new PlayerParticipation(
                player.getUUID(),
                player.getName().getString(),
                System.currentTimeMillis()
        ));
    }

    private static void handleDeath(LivingEntity entity, DamageSource damageSource) {
        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }

        MinecraftServer server = ServerContextHolder.getServer();
        if (server == null) {
            return;
        }

        processDeath(server, victim, resolvePlayerParticipant(damageSource), damageSource);
    }

    private static void processDeath(MinecraftServer server, ServerPlayer victim, ServerPlayer directParticipant, DamageSource damageSource) {
        UUID victimUuid = victim.getUUID();
        if (!processedDeaths.add(victimUuid)) {
            return;
        }

        long now = System.currentTimeMillis();
        PlayerParticipation participation = recentPlayerParticipation.get(victimUuid);
        if (participation != null && (now - participation.timestamp()) > PLAYER_PARTICIPATION_WINDOW_MS) {
            participation = null;
        }

        boolean playerInvolved = directParticipant != null || participation != null;
        if (!playerInvolved) {
            processedDeaths.remove(victimUuid);
            return;
        }

        boolean renamedWeaponKill = false;
        if (directParticipant != null) {
            renamedWeaponKill = isRenamedWeaponKill(damageSource, directParticipant, victim);
        }

        if (!renamedWeaponKill && participation != null) {
            ServerPlayer participatingPlayer = server.getPlayerList().getPlayer(participation.playerUuid());
            if (participatingPlayer != null) {
                renamedWeaponKill = isRenamedWeaponKill(damageSource, participatingPlayer, victim);
            }
        }

        if (!renamedWeaponKill) {
            processedDeaths.remove(victimUuid);
            return;
        }

        DebugMessenger.debug(server, "Renamed weapon kill detected for " + victim.getName().getString() + "; applying ghost state.");
        GhostStateManager.makeGhost(server, victim);
        summonCosmeticLightning(victim);
    }

    private static ServerPlayer resolvePlayerParticipant(DamageSource damageSource) {
        Entity attacker = damageSource.getEntity();
        if (attacker instanceof ServerPlayer killer) {
            return killer;
        }

        Entity source = damageSource.getEntity();
        if (source instanceof ServerPlayer killer) {
            return killer;
        }

        return null;
    }

    private static boolean isRenamedWeaponKill(DamageSource damageSource, ServerPlayer killer, ServerPlayer victim) {
        ItemStack weapon = damageSource.getWeaponItem();
        if ((weapon == null || weapon.isEmpty()) && killer != null) {
            weapon = killer.getMainHandItem();
        }

        if (weapon == null || weapon.isEmpty() || !weapon.has(DataComponents.CUSTOM_NAME)) {
            return false;
        }

        String weaponName = normalizeKillName(weapon.getHoverName().getString());
        String victimName = normalizeKillName(getOriginalPlayerName(victim));
        return !weaponName.isEmpty() && !victimName.isEmpty() && weaponName.equalsIgnoreCase(victimName);
    }

    private static String getOriginalPlayerName(ServerPlayer player) {
        if (player == null) {
            return "";
        }

        return IdentityStore.get(player.getUUID())
                .map(profile -> profile.name())
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> player.getGameProfile().name() == null ? "" : player.getGameProfile().name());
    }

    private static String normalizeKillName(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim();
        if (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first == '[' && last == ']') || (first == '(' && last == ')') || (first == '{' && last == '}')) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
        }

        return normalized;
    }

    private static void summonCosmeticLightning(ServerPlayer victim) {
        ServerLevel world = (ServerLevel) victim.level();
        if (world == null) {
            return;
        }

        LightningBolt lightning = new LightningBolt(EntityType.LIGHTNING_BOLT, world);
        lightning.setVisualOnly(true);
        lightning.setPos(victim.getX(), victim.getY(), victim.getZ());
        world.addFreshEntity(lightning);
    }

    private static void pruneParticipation(long now) {
        recentPlayerParticipation.entrySet().removeIf(entry -> (now - entry.getValue().timestamp()) > PLAYER_PARTICIPATION_WINDOW_MS);
    }

    private record PlayerParticipation(UUID playerUuid, String playerName, long timestamp) {
    }
}
