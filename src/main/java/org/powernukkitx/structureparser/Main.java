package org.powernukkitx.structureparser;

import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.stream.PGZIPOutputStream;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.level.structure.JeStructure;
import cn.nukkit.registry.Registries;
import cn.nukkit.registry.mappings.BlockMappings;
import cn.nukkit.registry.mappings.MappingRegistries;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class Main {

    /**
     * Recursively scans the folder and writes each NBT file into a nested CompoundTag.
     */
    protected static void checkFolder(File folder, CompoundTag root) throws IOException {
        CompoundTag tag = new CompoundTag();
        if(folder.isDirectory()) {
            for(File file : folder.listFiles()) {
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
                tag.putBoolean("PNX", true);
                List<Integer> palette = new ArrayList<>();
                byte[] blocks = new byte[x*y*z];
                for(JeStructure.StructureBlockInstance b : structure.getBlockInstances()) {
                    int hash = b.block.state.blockStateHash();
                    if(!palette.contains(hash)) palette.add(hash);
                    int idx = palette.indexOf(hash);
                    int index = b.x + (b.y * x) + (b.z * x * y);
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
            } else System.err.println(folder.getName() + " is not an NBT file!");
        }
        root.putCompound(folder.getName().split("\\.")[0], tag);
    }

    private static CompoundTag getOrCreateNestedTag(CompoundTag root, String[] path) {
        CompoundTag current = root;
        for (String part : path) {
            if (!current.contains(part) || !(current.get(part) instanceof CompoundTag)) {
                CompoundTag newTag = new CompoundTag();
                current.put(part, newTag);
                current = newTag;
            } else {
                current = (CompoundTag) current.get(part);
            }
        }
        return current;
    }

    // === Verification method unchanged ===
    private static boolean verifyWrittenFile(String filename) {
        System.out.println("\n=== VERIFYING WRITTEN FILE ===");
        File file = new File(filename);

        if (!file.exists()) {
            System.err.println("✗ File does not exist: " + filename);
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
        BlockMappings map = MappingRegistries.BLOCKS; //load mappings

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

        String outputFile = "structures.nbt";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             PGZIPOutputStream gzip = new PGZIPOutputStream(baos)) {

            NBTIO.write(root, gzip);
            gzip.finish();

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(baos.toByteArray());
                fos.flush();
            }
            System.out.println("✓ Write completed!");

        } catch (Exception e) {
            System.err.println("✗ Failed to write " + outputFile + ": " + e.getMessage());
            e.printStackTrace();
            return;
        }

        verifyWrittenFile(outputFile);
        System.out.println("\n=== PROCESS FINISHED ===");
    }
}
