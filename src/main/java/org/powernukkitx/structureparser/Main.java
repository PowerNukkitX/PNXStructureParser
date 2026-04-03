package org.powernukkitx.structureparser;

import cn.nukkit.block.Block;
import cn.nukkit.block.BlockState;
import cn.nukkit.block.BlockUnknown;
import cn.nukkit.level.structure.JeStructure;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.registry.Registries;
import cn.nukkit.registry.mappings.JeBlockState;
import cn.nukkit.registry.mappings.MappingRegistries;
import cn.nukkit.registry.mappings.populator.BlockRegistryPopulator;
import lombok.SneakyThrows;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Main {

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
                CompoundTag javaTag = NBTIO.readCompressed(new FileInputStream(folder));
                JeStructure structure = JeStructure.fromNbt(javaTag);
                int x = structure.getSizeX();
                int y = structure.getSizeY();
                int z = structure.getSizeZ();
                tag.putIntArray("size", new int[] {x, y, z});
                List<Integer> palette = new ArrayList<>();
                byte[] blocks = new byte[x*y*z];
                ListTag<CompoundTag> jigsaw = new ListTag<>();
                for(JeStructure.StructureBlockInstance instance : structure.getBlockInstances()) {
                    JeStructure.StructureBlocks block = instance.block;
                    int hash = block.state.blockStateHash();
                    if(!block.compoundTag.isEmpty()) {
                        if(block.compoundTag.getString("id").equals("minecraft:jigsaw")) {
                            CompoundTag nbt = block.compoundTag.copy();
                            nbt.putIntArray("pos", new int[] {instance.x, instance.y, instance.z});
                            nbt.remove("id");
                            BlockState state = MappingRegistries.BLOCKS.getPNXBlock(new JeBlockState(nbt.getString("final_state")));
                            if(state == null) {
                                Block block1 = Registries.BLOCK.get(nbt.getString("final_state"));
                                if(block1 != null) state = block1.getBlockState();
                            };
                            if(state == null) state = BlockUnknown.PROPERTIES.getDefaultState();
                            nbt.putInt("final_state", state.blockStateHash());
                            jigsaw.add(nbt);
                        }
                    }
                    if(!palette.contains(hash)) palette.add(hash);
                    int idx = palette.indexOf(hash);
                    int index = instance.x + (instance.y * x) + (instance.z * x * y);
                    blocks[index] = (byte) (idx + 1);
                }
                if(palette.size() > 127) {
                    System.out.println("PALETTE BIGGER THAN 127");
                }
                ListTag<IntTag> paletteTag = new ListTag<>(Tag.TAG_Int);
                for(int i = 0; i < palette.size(); i++) {
                    paletteTag.add(i, new IntTag(palette.get(i)));
                }
                tag.putList("palette", paletteTag);
                tag.putByteArray("blocks", blocks);
                if(jigsaw.size() > 0) tag.putList("jigsaw", jigsaw);
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

        try (FileInputStream fis = new FileInputStream(file)) {
            CompoundTag readBack = NBTIO.readCompressed(fis);
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
            NBTIO.writeGZIPCompressed(root, new FileOutputStream(output));
            System.out.println("✓ Write completed!");
        } catch (IOException e) {
            System.err.println("✗ Failed to write " + output.getName() + ": " + e.getMessage());
            throw new RuntimeException(e);
        }

        verifyWrittenFile(output);
        System.out.println("\n=== PROCESS FINISHED ===");
    }
}
