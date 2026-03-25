package com.unknownmod.worldgen;

import com.unknownmod.util.DebugMessenger;
import com.unknownmod.worldgen.ChunkWorldgenManager.FillMode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Optional;

public final class ChunkOverrideGenerator {
    private ChunkOverrideGenerator() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_GENERATE.register(ChunkOverrideGenerator::onChunkGenerate);
    }

    private static void onChunkGenerate(ServerWorld world, WorldChunk chunk) {
        if (!World.OVERWORLD.equals(world.getRegistryKey())) {
            return;
        }

        ChunkWorldgenManager.CompiledChunkWorldgenConfig config = ChunkWorldgenManager.getCompiled();
        Random random = seedRandom(world, chunk.getPos());
        FillMode mode = config.pickMode(random);
        if (mode == FillMode.NONE) {
            return;
        }

        ChunkWorldgenManager.CompiledChunkOverride override = config.pickOverride(random, mode).orElse(null);
        if (override == null) {
            return;
        }

        if (mode == FillMode.FULL) {
            applyFullOverride(world, chunk, override, random);
        } else {
            applyPartialOverride(world, chunk, override, random, config);
        }

        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        DebugMessenger.debug(server, "Applied " + mode.name().toLowerCase() + " worldgen override '" + override.name() + "' to chunk " + chunk.getPos().x + ", " + chunk.getPos().z + ".");
    }

    private static void applyFullOverride(ServerWorld world, WorldChunk chunk, ChunkWorldgenManager.CompiledChunkOverride override, Random random) {
        int minY = world.getDimension().minY();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null) {
                continue;
            }

            int sectionY = minY + (sectionIndex << 4);
            for (int localY = 0; localY < 16; localY++) {
                int worldY = sectionY + localY;
                if (worldY < minY || worldY >= minY + world.getDimension().height()) {
                    continue;
                }

                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        Optional<BlockState> replacement = override.pickBlock(random);
                        if (replacement.isEmpty()) {
                            continue;
                        }

                        section.setBlockState(localX, localY, localZ, replacement.get(), false);
                    }
                }
            }
        }

        chunk.markNeedsSaving();
    }

    private static void applyPartialOverride(
            ServerWorld world,
            WorldChunk chunk,
            ChunkWorldgenManager.CompiledChunkOverride override,
            Random random,
            ChunkWorldgenManager.CompiledChunkWorldgenConfig config
    ) {
        int minY = world.getDimension().minY();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            if (section == null || section.isEmpty()) {
                continue;
            }

            int sectionY = minY + (sectionIndex << 4);
            for (int localY = 0; localY < 16; localY++) {
                int worldY = sectionY + localY;
                if (worldY < minY || worldY >= minY + world.getDimension().height()) {
                    continue;
                }

                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        BlockState current = section.getBlockState(localX, localY, localZ);
                        if (!config.shouldReplaceInPartialMode(current)) {
                            continue;
                        }

                        Optional<BlockState> replacement = override.pickBlock(random);
                        if (replacement.isEmpty()) {
                            continue;
                        }

                        section.setBlockState(localX, localY, localZ, replacement.get(), false);
                    }
                }
            }
        }

        chunk.markNeedsSaving();
    }

    private static Random seedRandom(ServerWorld world, ChunkPos pos) {
        long seed = world.getSeed();
        long mixedSeed = seed
                ^ ((long) pos.x * 341873128712L)
                ^ ((long) pos.z * 132897987541L);
        return Random.create(mixedSeed);
    }
}
