package com.unknownmod.event;

import com.unknownmod.state.GhostStateManager;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.ServerContextHolder;
import com.unknownmod.util.DebugMessenger;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerDeathHandler {
    private static final long PLAYER_PARTICIPATION_WINDOW_MS = 15_000L;

    private static final Set<UUID> processedDeaths = new HashSet<>();
    private static final Map<UUID, PlayerParticipation> recentPlayerParticipation = new HashMap<>();

    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(PlayerDeathHandler::handleDirectKill);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(PlayerDeathHandler::trackDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(PlayerDeathHandler::handleDeath);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processedDeaths.clear();
            pruneParticipation(System.currentTimeMillis());
        });
    }

    private static void handleDirectKill(ServerWorld world, Entity entity, LivingEntity killedEntity, DamageSource damageSource) {
        if (!(entity instanceof ServerPlayerEntity killer) || !(killedEntity instanceof ServerPlayerEntity victim)) {
            return;
        }

        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }

        processDeath(server, victim, killer, damageSource);
    }

    private static void trackDamage(LivingEntity entity, DamageSource damageSource, float dealtDamage, float takenDamage, boolean blocked) {
        if (!(entity instanceof ServerPlayerEntity victim)) {
            return;
        }

        ServerPlayerEntity player = resolvePlayerParticipant(damageSource);
        if (player == null) {
            return;
        }

        recentPlayerParticipation.put(victim.getUuid(), new PlayerParticipation(
                player.getUuid(),
                player.getName().getString(),
                System.currentTimeMillis()
        ));
    }

    private static void handleDeath(LivingEntity entity, DamageSource damageSource) {
        if (!(entity instanceof ServerPlayerEntity victim)) {
            return;
        }

        MinecraftServer server = ServerContextHolder.getServer();
        if (server == null) {
            return;
        }

        processDeath(server, victim, resolvePlayerParticipant(damageSource), damageSource);
    }

    private static void processDeath(MinecraftServer server, ServerPlayerEntity victim, ServerPlayerEntity directParticipant, DamageSource damageSource) {
        UUID victimUuid = victim.getUuid();
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
            ServerPlayerEntity participatingPlayer = server.getPlayerManager().getPlayer(participation.playerUuid());
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

    private static ServerPlayerEntity resolvePlayerParticipant(DamageSource damageSource) {
        Entity attacker = damageSource.getAttacker();
        if (attacker instanceof ServerPlayerEntity killer) {
            return killer;
        }

        Entity source = damageSource.getSource();
        if (source instanceof ServerPlayerEntity killer) {
            return killer;
        }

        return null;
    }

    private static boolean isRenamedWeaponKill(DamageSource damageSource, ServerPlayerEntity killer, ServerPlayerEntity victim) {
        ItemStack weapon = damageSource.getWeaponStack();
        if ((weapon == null || weapon.isEmpty()) && killer != null) {
            weapon = killer.getMainHandStack();
        }

        if (weapon == null || weapon.isEmpty() || !weapon.contains(DataComponentTypes.CUSTOM_NAME)) {
            return false;
        }

        String weaponName = normalizeKillName(weapon.getName().getString());
        String victimName = normalizeKillName(getOriginalPlayerName(victim));
        return !weaponName.isEmpty() && !victimName.isEmpty() && weaponName.equalsIgnoreCase(victimName);
    }

    private static String getOriginalPlayerName(ServerPlayerEntity player) {
        if (player == null) {
            return "";
        }

        return IdentityStore.get(player.getUuid())
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

    private static void summonCosmeticLightning(ServerPlayerEntity victim) {
        ServerWorld world = victim.getEntityWorld();
        if (world == null) {
            return;
        }

        LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
        lightning.setCosmetic(true);
        lightning.refreshPositionAndAngles(victim.getX(), victim.getY(), victim.getZ(), 0.0f, 0.0f);
        world.spawnEntity(lightning);
    }

    private static void pruneParticipation(long now) {
        recentPlayerParticipation.entrySet().removeIf(entry -> (now - entry.getValue().timestamp()) > PLAYER_PARTICIPATION_WINDOW_MS);
    }

    private record PlayerParticipation(UUID playerUuid, String playerName, long timestamp) {
    }
}
