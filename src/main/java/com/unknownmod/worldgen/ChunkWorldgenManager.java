package com.unknownmod.worldgen;

import com.unknownmod.UnknownMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChunkWorldgenManager {
    private static final Pattern LEGACY_ALLOW_PATTERN = Pattern.compile("^(\\s*-\\s*)!(.+)$");
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("unknown");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("worldgen.yml");
    private static final YamlConfigurationLoader LOADER = YamlConfigurationLoader.builder()
            .path(CONFIG_PATH)
            .nodeStyle(NodeStyle.BLOCK)
            .build();

    private static ChunkWorldgenConfig config = ChunkWorldgenConfig.createDefault();
    private static CompiledChunkWorldgenConfig compiled = CompiledChunkWorldgenConfig.empty();

    private ChunkWorldgenManager() {
    }

    public static synchronized void load() {
        try {
            if (Files.notExists(CONFIG_PATH)) {
                config = ChunkWorldgenConfig.createDefault();
                compiled = compile(config);
                save();
                return;
            }

            CommentedConfigurationNode root = LOADER.load();
            ChunkWorldgenConfig loaded = root.get(ChunkWorldgenConfig.class);
            if (loaded == null) {
                loaded = ChunkWorldgenConfig.createDefault();
            }

            boolean changed = normalize(loaded);
            config = loaded;
            compiled = compile(config);
            if (changed) {
                save();
            }
        } catch (Exception e) {
            UnknownMod.LOGGER.error("Failed to load worldgen config from " + CONFIG_PATH, e);
            if (repairLegacyPartialExclusions()) {
                try {
                    CommentedConfigurationNode root = LOADER.load();
                    ChunkWorldgenConfig loaded = root.get(ChunkWorldgenConfig.class);
                    if (loaded == null) {
                        loaded = ChunkWorldgenConfig.createDefault();
                    }

                    boolean changed = normalize(loaded);
                    config = loaded;
                    compiled = compile(config);
                    if (changed) {
                        save();
                    }
                    return;
                } catch (Exception retryError) {
                    UnknownMod.LOGGER.error("Failed to reload repaired worldgen config from " + CONFIG_PATH, retryError);
                }
            }

            config = ChunkWorldgenConfig.createDefault();
            compiled = compile(config);
            save();
        }
    }

    public static synchronized void save() {
        try {
            normalize(config);
            Files.createDirectories(CONFIG_DIR);
            CommentedConfigurationNode root = LOADER.createNode();
            root.set(ChunkWorldgenConfig.class, config);
            LOADER.save(root);
        } catch (Exception e) {
            UnknownMod.LOGGER.error("Failed to save worldgen config to " + CONFIG_PATH, e);
        }
    }

    public static synchronized ChunkWorldgenConfig getConfig() {
        return config;
    }

    public static synchronized CompiledChunkWorldgenConfig getCompiled() {
        return compiled;
    }

    private static CompiledChunkWorldgenConfig compile(ChunkWorldgenConfig raw) {
        List<WeightedEntry<CompiledChunkOverride>> fullOverrides = new ArrayList<>();
        List<WeightedEntry<CompiledChunkOverride>> partialOverrides = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();

        if (raw != null && raw.partialExclusions != null) {
            exclusions.addAll(raw.partialExclusions);
        }

        if (raw == null || raw.overrides == null) {
            return new CompiledChunkWorldgenConfig(
                    0,
                    0,
                    exclusions,
                    WeightedPool.<CompiledChunkOverride>empty(),
                    WeightedPool.<CompiledChunkOverride>empty()
            );
        }

        for (Map.Entry<String, ChunkWorldgenConfig.ChunkOverride> entry : raw.overrides.entrySet()) {
            String name = entry.getKey();
            ChunkWorldgenConfig.ChunkOverride source = entry.getValue();
            if (source == null || source.weight <= 0) {
                continue;
            }

            OverrideMode mode = resolveType(source);
            if (source.blocks == null || source.blocks.isEmpty()) {
                resolveBlockState(name).ifPresentOrElse(
                        state -> addOverride(fullOverrides, partialOverrides, mode, CompiledChunkOverride.simple(name, state), source.weight),
                        () -> UnknownMod.LOGGER.warn("Worldgen override '{}' points to unknown block '{}'", name, name)
                );
                continue;
            }

            List<WeightedEntry<CompiledBlockChoice>> blockPool = new ArrayList<>();
            for (Map.Entry<String, Integer> blockEntry : source.blocks.entrySet()) {
                int blockWeight = blockEntry.getValue() == null ? 0 : blockEntry.getValue();
                if (blockWeight <= 0) {
                    continue;
                }

                if ("save".equalsIgnoreCase(blockEntry.getKey())) {
                    blockPool.add(new WeightedEntry<>(CompiledBlockChoice.save(), blockWeight));
                    continue;
                }

                resolveBlockState(blockEntry.getKey()).ifPresentOrElse(
                        state -> blockPool.add(new WeightedEntry<>(CompiledBlockChoice.block(state), blockWeight)),
                        () -> UnknownMod.LOGGER.warn("Worldgen pack '{}' contains unknown block '{}'", name, blockEntry.getKey())
                );
            }

            if (blockPool.isEmpty()) {
                UnknownMod.LOGGER.warn("Worldgen pack '{}' has no valid blocks and will be skipped", name);
                continue;
            }

            addOverride(fullOverrides, partialOverrides, mode, CompiledChunkOverride.pack(name, WeightedPool.of(blockPool)), source.weight);
        }

        return new CompiledChunkWorldgenConfig(
                Math.max(0, raw.chance),
                Math.max(0, raw.partialChance),
                exclusions,
                WeightedPool.of(fullOverrides),
                WeightedPool.of(partialOverrides)
        );
    }

    private static Optional<BlockState> resolveBlockState(String rawId) {
        Optional<Identifier> id = parseIdentifier(rawId);
        if (id.isEmpty() || !Registries.BLOCK.containsId(id.get())) {
            return Optional.empty();
        }

        Block block = Registries.BLOCK.get(id.get());
        return Optional.of(block.getDefaultState());
    }

    private static Optional<Identifier> parseIdentifier(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }

        try {
            if (rawId.contains(":")) {
                return Optional.ofNullable(Identifier.tryParse(rawId));
            }

            return Optional.of(Identifier.ofVanilla(rawId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void addOverride(
            List<WeightedEntry<CompiledChunkOverride>> fullOverrides,
            List<WeightedEntry<CompiledChunkOverride>> partialOverrides,
            OverrideMode mode,
            CompiledChunkOverride override,
            int weight
    ) {
        WeightedEntry<CompiledChunkOverride> weighted = new WeightedEntry<>(override, weight);
        if (mode == OverrideMode.ALL) {
            fullOverrides.add(weighted);
            partialOverrides.add(weighted);
            return;
        }

        if (mode == OverrideMode.FULL) {
            fullOverrides.add(weighted);
        } else {
            partialOverrides.add(weighted);
        }
    }

    private static OverrideMode resolveType(ChunkWorldgenConfig.ChunkOverride source) {
        String rawType = source.type == null ? "" : source.type.trim().toLowerCase(Locale.ROOT);
        if ("full".equals(rawType)) {
            return OverrideMode.FULL;
        }
        if ("part".equals(rawType) || "partial".equals(rawType)) {
            return OverrideMode.PART;
        }
        if ("all".equals(rawType)) {
            return OverrideMode.ALL;
        }

        return OverrideMode.ALL;
    }

    private static boolean normalize(ChunkWorldgenConfig config) {
        boolean changed = false;

        if (config == null) {
            return false;
        }

        if (config.partialExclusions == null) {
            config.partialExclusions = new ArrayList<>(ChunkWorldgenConfig.defaultPartialExclusions());
            changed = true;
        }

        if (config.overrides == null) {
            config.overrides = new LinkedHashMap<>();
            changed = true;
        }

        for (ChunkWorldgenConfig.ChunkOverride override : config.overrides.values()) {
            if (override == null) {
                continue;
            }

            if (override.blocks == null) {
                override.blocks = new LinkedHashMap<>();
                changed = true;
            }

            String resolvedType = normalizeType(override);
            if (!resolvedType.equals(override.type)) {
                override.type = resolvedType;
                changed = true;
            }
        }

        return changed;
    }

    private static String normalizeType(ChunkWorldgenConfig.ChunkOverride override) {
        String rawType = override.type == null ? "" : override.type.trim().toLowerCase(Locale.ROOT);
        if ("full".equals(rawType) || "part".equals(rawType) || "partial".equals(rawType) || "all".equals(rawType)) {
            return "partial".equals(rawType) ? "part" : rawType;
        }

        return "all";
    }

    private static boolean repairLegacyPartialExclusions() {
        try {
            if (Files.notExists(CONFIG_PATH)) {
                return false;
            }

            String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            String repaired = repairLegacyPartialExclusions(content);
            if (repaired == null) {
                return false;
            }

            Files.writeString(CONFIG_PATH, repaired, StandardCharsets.UTF_8);
            return true;
        } catch (Exception repairError) {
            UnknownMod.LOGGER.warn("Failed to repair legacy worldgen config at " + CONFIG_PATH, repairError);
            return false;
        }
    }

    private static String repairLegacyPartialExclusions(String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder repaired = new StringBuilder(content.length());
        boolean inPartialExclusions = false;
        boolean changed = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if ("partial-exclusions:".equals(trimmed)) {
                inPartialExclusions = true;
                repaired.append(line).append(System.lineSeparator());
                continue;
            }

            if (inPartialExclusions) {
                if (line.isBlank()) {
                    repaired.append(line).append(System.lineSeparator());
                    continue;
                }

                if (!line.startsWith(" ") && !line.startsWith("\t")) {
                    inPartialExclusions = false;
                } else {
                    Matcher matcher = LEGACY_ALLOW_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        line = matcher.group(1) + "allow:" + matcher.group(2).trim();
                        changed = true;
                    }
                }
            }

            repaired.append(line).append(System.lineSeparator());
        }

        return changed ? repaired.toString() : null;
    }

    public record CompiledChunkWorldgenConfig(
            int fullChance,
            int partialChance,
            List<String> partialExclusions,
            WeightedPool<CompiledChunkOverride> fullOverrides,
            WeightedPool<CompiledChunkOverride> partialOverrides
    ) {
        public static CompiledChunkWorldgenConfig empty() {
            return new CompiledChunkWorldgenConfig(
                    0,
                    0,
                    List.of(),
                    WeightedPool.<CompiledChunkOverride>empty(),
                    WeightedPool.<CompiledChunkOverride>empty()
            );
        }

        public FillMode pickMode(Random random) {
            if (!fullOverrides.isEmpty() && fullChance > 0 && random.nextInt(fullChance) == 0) {
                return FillMode.FULL;
            }

            if (!partialOverrides.isEmpty() && partialChance > 0 && random.nextInt(partialChance) == 0) {
                return FillMode.PARTIAL;
            }

            return FillMode.NONE;
        }

        public Optional<CompiledChunkOverride> pickOverride(Random random, FillMode mode) {
            return switch (mode) {
                case FULL -> fullOverrides.pick(random);
                case PARTIAL -> partialOverrides.pick(random);
                default -> Optional.empty();
            };
        }

        public boolean shouldReplaceInPartialMode(BlockState state) {
            if (partialExclusions == null || partialExclusions.isEmpty()) {
                return true;
            }

            String blockId = Registries.BLOCK.getId(state.getBlock()).toString().toLowerCase(Locale.ROOT);
            for (String keyword : partialExclusions) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }

                String normalized = keyword.trim().toLowerCase(Locale.ROOT);
                if (normalized.startsWith("!") || normalized.startsWith("allow:")) {
                    String exact = normalized.startsWith("!")
                            ? normalized.substring(1).trim()
                            : normalized.substring("allow:".length()).trim();
                    if (!exact.isEmpty() && matchesExactBlockId(blockId, exact)) {
                        return true;
                    }
                }
            }

            for (String keyword : partialExclusions) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }

                String normalized = keyword.trim().toLowerCase(Locale.ROOT);
                if (normalized.startsWith("!") || normalized.startsWith("allow:")) {
                    continue;
                }

                if (normalized.startsWith("=")) {
                    String exact = normalized.substring(1).trim();
                    if (!exact.isEmpty() && matchesExactBlockId(blockId, exact)) {
                        return false;
                    }
                    continue;
                }

                if (blockId.contains(normalized)) {
                    return false;
                }
            }

            return true;
        }

        private boolean matchesExactBlockId(String blockId, String keyword) {
            if (blockId.equals(keyword)) {
                return true;
            }

            if (!keyword.contains(":")) {
                return blockId.equals("minecraft:" + keyword);
            }

            return false;
        }
    }

    public enum FillMode {
        NONE,
        FULL,
        PARTIAL
    }

    private enum OverrideMode {
        ALL,
        FULL,
        PART
    }

    public record CompiledChunkOverride(String name, BlockState fixedState, WeightedPool<CompiledBlockChoice> blockPool) {
        public static CompiledChunkOverride simple(String name, BlockState state) {
            return new CompiledChunkOverride(name, state, WeightedPool.<CompiledBlockChoice>empty());
        }

        public static CompiledChunkOverride pack(String name, WeightedPool<CompiledBlockChoice> blockPool) {
            return new CompiledChunkOverride(name, null, blockPool);
        }

        public Optional<BlockState> pickBlock(Random random) {
            if (fixedState != null) {
                return Optional.of(fixedState);
            }

            CompiledBlockChoice choice = blockPool.pick(random).orElse(null);
            if (choice == null) {
                return Optional.empty();
            }

            if (choice.keepCurrent()) {
                return Optional.empty();
            }

            return Optional.of(choice.state());
        }
    }

    public record CompiledBlockChoice(boolean keepCurrent, BlockState state) {
        public static CompiledBlockChoice save() {
            return new CompiledBlockChoice(true, null);
        }

        public static CompiledBlockChoice block(BlockState state) {
            return new CompiledBlockChoice(false, state);
        }
    }

    private record WeightedEntry<T>(T value, int weight) {
    }

    private static final class WeightedPool<T> {
        private final List<WeightedEntry<T>> entries;
        private final int totalWeight;

        private WeightedPool(List<WeightedEntry<T>> entries) {
            this.entries = List.copyOf(entries);
            int total = 0;
            for (WeightedEntry<T> entry : this.entries) {
                if (entry != null && entry.weight() > 0) {
                    total += entry.weight();
                }
            }
            this.totalWeight = total;
        }

        private static <T> WeightedPool<T> empty() {
            return new WeightedPool<>(List.of());
        }

        private static <T> WeightedPool<T> of(List<WeightedEntry<T>> entries) {
            return new WeightedPool<>(entries);
        }

        private boolean isEmpty() {
            return entries.isEmpty() || totalWeight <= 0;
        }

        private Optional<T> pick(Random random) {
            if (isEmpty()) {
                return Optional.empty();
            }

            int roll = random.nextInt(totalWeight);
            for (WeightedEntry<T> entry : entries) {
                if (entry == null || entry.weight() <= 0) {
                    continue;
                }

                roll -= entry.weight();
                if (roll < 0) {
                    return Optional.ofNullable(entry.value());
                }
            }

            return Optional.empty();
        }
    }
}
