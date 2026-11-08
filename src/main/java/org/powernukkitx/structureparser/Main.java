package org.powernukkitx.structureparser;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockDataVersion;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.identifier.BedrockBlockIdentifierResolver;
import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.JavaBlockIdentifierResolver;
import com.hivemc.chunker.mapping.identifier.Identifier;
import org.powernukkitx.block.BlockState;
import org.powernukkitx.block.BlockUnknown;
import org.powernukkitx.nbt.tag.*;
import org.powernukkitx.registry.Registries;
import org.powernukkitx.registry.mappings.populator.BlockRegistryPopulator;
import lombok.SneakyThrows;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.*;
import java.util.*;

public class Main {

    private static final WorldConverter CHUNKER = new WorldConverter(UUID.randomUUID());
    private static final Map<String, List<BlockState>> BEDROCK_STATES = new HashMap<>();

    private static void indexBedrockStates() {
        BEDROCK_STATES.clear();
        for (BlockState state : Registries.BLOCKSTATE.getAllState()) {
            BEDROCK_STATES.computeIfAbsent(state.getIdentifier(), ignored -> new ArrayList<>()).add(state);
        }
    }

    private static Object normalizeStateValue(Object value) {
        if (value instanceof Boolean bool) return bool ? 1L : 0L;
        if (value instanceof Number number) return number.longValue();
        return value;
    }

    private static BlockState toPnxBlockState(
            Identifier javaIdentifier,
            JavaBlockIdentifierResolver javaResolver,
            BedrockBlockIdentifierResolver bedrockResolver
    ) {
        var chunkerState = javaResolver.to(javaIdentifier)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Java block state: " + javaIdentifier.toStateString()));
        Identifier bedrockIdentifier = bedrockResolver.from(chunkerState)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Bedrock mapping for: " + javaIdentifier.toStateString()));

        List<BlockState> candidates = BEDROCK_STATES.getOrDefault(
                bedrockIdentifier.getIdentifier(), Collections.emptyList());
        for (BlockState candidate : candidates) {
            NbtMap states = candidate.getBlockStateTag().getCompound("states");
            boolean matches = true;
            for (Map.Entry<String, Object> entry : bedrockIdentifier.getBoxedStates().entrySet()) {
                // In Bedrock, waterlogging is represented by a second block layer.
                if (entry.getKey().equals("waterlogged")) continue;
                if (!states.containsKey(entry.getKey()) || !Objects.equals(
                        normalizeStateValue(states.get(entry.getKey())),
                        normalizeStateValue(entry.getValue()))) {
                    matches = false;
                    break;
                }
            }
            if (matches) return candidate;
        }

        throw new IllegalArgumentException(
                "No PNX block state for Bedrock state: " + bedrockIdentifier.toStateString());
    }

    private static Identifier readPaletteEntry(CompoundTag paletteEntry) {
        Map<String, Object> properties = new HashMap<>();
        String propertiesKey = paletteEntry.containsCompound("properties") ? "properties" : "Properties";
        if (paletteEntry.containsCompound(propertiesKey)) {
            properties.putAll(paletteEntry.getCompound(propertiesKey).parseValue());
        }
        String identifier = paletteEntry.containsString("id")
                ? paletteEntry.getString("id")
                : paletteEntry.getString("Name");
        return Identifier.fromBoxed(identifier, properties);
    }

    private static Identifier parseJavaBlockState(String value) {
        int bracket = value.indexOf('[');
        if (bracket < 0) return new Identifier(value);

        Map<String, Object> properties = new HashMap<>();
        String attributes = value.substring(bracket + 1, value.length() - 1);
        if (!attributes.isBlank()) {
            for (String attribute : attributes.split(",")) {
                String[] parts = attribute.split("=", 2);
                if (parts.length == 2) properties.put(parts[0], parts[1]);
            }
        }
        return Identifier.fromBoxed(value.substring(0, bracket), properties);
    }

    /**
     * Recursively scans the folder and writes each NBT file into a nested CompoundTag.
     */
    @SneakyThrows
    protected static void checkFolder(File folder, CompoundTag root) {
        CompoundTag tag = root.getCompound(folder.getName().split("\\.")[0]);
        if(folder.isDirectory()) {
            for(File file : Objects.requireNonNull(folder.listFiles())) {
                checkFolder(file, tag);
            }
        } else {
            if(folder.getName().endsWith(".nbt")) {
                CompoundTag object = new CompoundTag();
                try (FileInputStream fis = new FileInputStream(folder);
                     NBTInputStream stream = NbtUtils.createGZIPReader(fis)) {
                    CompoundTag javaTag = CompoundTag.fromNetwork((NbtMap) stream.readTag());
                    ListTag<IntTag> size = javaTag.getList("size", IntTag.class);
                    int x = size.get(0).data;
                    int y = size.get(1).data;
                    int z = size.get(2).data;
                    object.putIntArray("size", new int[]{x, y, z});

                    JavaDataVersion javaVersion = JavaDataVersion.getNearestVersion(javaTag.getInt("DataVersion"));
                    JavaBlockIdentifierResolver javaResolver = new JavaBlockIdentifierResolver(
                            CHUNKER, javaVersion.getVersion(), false, false);
                    BedrockBlockIdentifierResolver bedrockResolver = new BedrockBlockIdentifierResolver(
                            CHUNKER, BedrockDataVersion.latest().getVersion(), false, false);

                    ListTag<CompoundTag> javaPalette;
                    if (javaTag.containsList("palette")) {
                        javaPalette = javaTag.getList("palette", CompoundTag.class);
                    } else {
                        ListTag<? extends Tag> palettes = javaTag.getList("palettes");
                        if (palettes.size() == 0 || !(palettes.get(0) instanceof ListTag<?> firstPalette)) {
                            throw new IllegalArgumentException("Structure has no palette");
                        }
                        @SuppressWarnings("unchecked")
                        ListTag<CompoundTag> selectedPalette = (ListTag<CompoundTag>) firstPalette;
                        javaPalette = selectedPalette;
                    }
                    List<Integer> palette = new ArrayList<>();
                    int[] paletteMapping = new int[javaPalette.size()];
                    for (int i = 0; i < javaPalette.size(); i++) {
                        int hash = toPnxBlockState(
                                readPaletteEntry(javaPalette.get(i)), javaResolver, bedrockResolver).blockStateHash();
                        if (!palette.contains(hash)) palette.add(hash);
                        paletteMapping[i] = palette.indexOf(hash);
                    }

                    byte[] blocks = new byte[x * y * z];
                    ListTag<CompoundTag> jigsaw = new ListTag<>();
                    for (CompoundTag block : javaTag.getList("blocks", CompoundTag.class).getAll()) {
                        ListTag<IntTag> pos = block.getList("pos", IntTag.class);
                        int blockX = pos.get(0).data;
                        int blockY = pos.get(1).data;
                        int blockZ = pos.get(2).data;
                        int sourceState = block.getInt("state");

                        if (block.containsCompound("nbt")) {
                            CompoundTag blockNbt = block.getCompound("nbt");
                            if (blockNbt.getString("id").equals("minecraft:jigsaw")) {
                                CompoundTag nbt = blockNbt.copy();
                                nbt.putIntArray("pos", new int[]{blockX, blockY, blockZ});
                                nbt.remove("id");
                                BlockState state;
                                try {
                                    state = toPnxBlockState(parseJavaBlockState(nbt.getString("final_state")),
                                            javaResolver, bedrockResolver);
                                } catch (IllegalArgumentException exception) {
                                    state = BlockUnknown.PROPERTIES.getDefaultState();
                                }
                                nbt.putInt("final_state", state.blockStateHash());
                                jigsaw.add(nbt);
                            }
                        }
                        int index = blockX + (blockY * x) + (blockZ * x * y);
                        blocks[index] = (byte) (paletteMapping[sourceState] + 1);
                    }
                    if (palette.size() > 127) {
                        System.out.println("PALETTE BIGGER THAN 127");
                    }
                    ListTag<IntTag> paletteTag = new ListTag<>(Tag.TAG_Int);
                    for (int i = 0; i < palette.size(); i++) {
                        paletteTag.add(i, new IntTag(palette.get(i)));
                    }
                    object.putList("palette", paletteTag);
                    object.putByteArray("blocks", blocks);
                    if (jigsaw.size() > 0) object.putList("jigsaw", jigsaw);
                    tag.putCompound("object", object);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to convert " + folder.getPath(), e);
                }
            } else System.err.println(folder.getName() + " is not an NBT file!");
        }
        root.putCompound(folder.getName().split("\\.")[0], tag);
    }

    // === Verification method unchanged ===
    private static boolean verifyWrittenFile(File file) {
        System.out.println("\n=== VERIFYING WRITTEN FILE ===");

        if (!file.exists()) {
            System.err.println("✗ File does not exist: " + file.getName());
            return false;
        }

        System.out.println("File exists. Size: " + file.length() + " bytes");

        try (FileInputStream fis = new FileInputStream(file);
        NBTInputStream stream = NbtUtils.createGZIPReader(fis)) {
            CompoundTag readBack = CompoundTag.fromNetwork((NbtMap) stream.readTag());
            System.out.println("✓ File successfully read back!");
            System.out.println("Structures found in file: " + readBack.getAllTags().size());
            return true;
        } catch (EOFException e) {
            System.err.println("✗ EOF Exception while reading back file - file is truncated!");
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            System.err.println("✗ Error reading back file: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Please provide a path to the directory containing structure NBT files.");
            return;
        }

        File baseDir = new File(args[0]);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            System.err.println("Invalid directory: " + args[0]);
            return;
        }

        Registries.BLOCK.init();
        Registries.BLOCKSTATE.init();
        BlockRegistryPopulator.initMapping();
        indexBedrockStates();

        System.out.println("=== SCANNING DIRECTORY: " + baseDir.getAbsolutePath() + " ===");
        CompoundTag root = new CompoundTag();
        for(File file : baseDir.listFiles()) {
            checkFolder(file, root);
        }

        System.out.println("\n=== WRITING STRUCTURES.NBT ===");
        if (root.getAllTags().isEmpty()) {
            System.err.println("No structures found! Aborting write.");
            return;
        }
        File output = new File("structures.nbt");
        try {
            if(output.exists()) output.createNewFile();
            try (FileOutputStream fos = new FileOutputStream(output);
                 NBTOutputStream stream = NbtUtils.createGZIPWriter(fos)) {
                stream.writeTag(root.toNetwork());
                System.out.println("✓ Write completed!");
            }
        } catch (IOException e) {
            System.err.println("✗ Failed to write " + output.getName() + ": " + e.getMessage());
            throw new RuntimeException(e);
        }

        verifyWrittenFile(output);
        System.out.println("\n=== PROCESS FINISHED ===");
    }
}
