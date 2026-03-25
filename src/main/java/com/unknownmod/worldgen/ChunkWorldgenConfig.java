package com.unknownmod.worldgen;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigSerializable
public class ChunkWorldgenConfig {
    @Setting("chance")
    public int chance = 100;

    @Setting("partial-chance")
    public int partialChance = 100;

    @Setting("partial-exclusions")
    public List<String> partialExclusions = new ArrayList<>(defaultPartialExclusions());

    @Setting("overrides")
    public Map<String, ChunkOverride> overrides = new LinkedHashMap<>();

    public static ChunkWorldgenConfig createDefault() {
        ChunkWorldgenConfig config = new ChunkWorldgenConfig();
        config.partialExclusions = new ArrayList<>(defaultPartialExclusions());

        ChunkOverride air = new ChunkOverride();
        air.type = "full";
        air.weight = 5;
        config.overrides.put("air", air);

        ChunkOverride stone = new ChunkOverride();
        stone.type = "full";
        stone.weight = 1;
        config.overrides.put("stone", stone);

        ChunkOverride packOres = new ChunkOverride();
        packOres.type = "part";
        packOres.weight = 2;
        packOres.blocks.put("diamond_ore", 1);
        packOres.blocks.put("iron_ore", 9);
        packOres.blocks.put("save", 20);
        config.overrides.put("pack_ores", packOres);

        return config;
    }

    public static List<String> defaultPartialExclusions() {
        return List.of(
                "air",
                "grass",
                "allow:grass_block",
                "fern",
                "flower",
                "sapling",
                "torch",
                "lantern",
                "candle",
                "vine",
                "lily_pad",
                "slab",
                "stairs",
                "door",
                "trapdoor",
                "button",
                "pressure_plate",
                "carpet",
                "sign",
                "hanging_sign",
                "banner",
                "rail",
                "ladder",
                "fence",
                "wall",
                "gate",
                "spore_blossom",
                "dripleaf",
                "kelp",
                "seagrass",
                "coral",
                "mushroom",
                "snow",
                "powder_snow",
                "cobweb",
                "scaffolding",
                "pointed_dripstone",
                "sweet_berry_bush",
                "berry_bush",
                "potted_",
                "water",
                "lava"
        );
    }

    @ConfigSerializable
    public static class ChunkOverride {
        @Setting("type")
        public String type = "";

        @Setting("weight")
        public int weight = 1;

        @Setting("blocks")
        public Map<String, Integer> blocks = new LinkedHashMap<>();
    }
}
