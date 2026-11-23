package me.ryanhamshire.GPFlags.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Durable, per-claim snapshots of the biome cells actually overwritten. */
public final class BiomeBackups {
    private final File directory;

    public BiomeBackups(Plugin plugin) {
        directory = new File(plugin.getDataFolder(), "biome-backups");
    }

    private YamlConfiguration read(File file) throws IOException {
        YamlConfiguration data = new YamlConfiguration();
        if (file.exists()) {
            try {
                data.load(file);
            } catch (InvalidConfigurationException e) {
                throw new IOException("Invalid biome backup: " + file, e);
            }
        }
        return data;
    }

    public void capture(long claimId, World world, int chunkX, int chunkZ,
                        int minX, int maxX, int minY, int maxY, int minZ, int maxZ) throws IOException {
        File folder = new File(directory, Long.toString(claimId));
        Files.createDirectories(folder.toPath());
        File file = new File(folder, world.getUID() + "_" + chunkX + "_" + chunkZ + ".yml");
        YamlConfiguration data = read(file);
        data.set("world", world.getUID().toString());
        data.set("chunk-x", chunkX);
        data.set("chunk-z", chunkZ);
        // Minecraft stores biomes in 4x4x4 cells, including at claim boundaries.
        for (int x = minX >> 2; x <= maxX >> 2; x++) {
            for (int z = minZ >> 2; z <= maxZ >> 2; z++) {
                for (int y = minY >> 2; y <= maxY >> 2; y++) {
                    String key = "cells." + x + "," + y + "," + z;
                    if (!data.contains(key)) {
                        data.set(key, world.getBiome(x << 2, y << 2, z << 2).getKey().toString());
                    }
                }
            }
        }
        File temporary = new File(folder, file.getName() + ".tmp");
        data.save(temporary);
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    public void restore(long claimId, World world) throws IOException {
        File folder = new File(directory, Long.toString(claimId));
        if (!folder.exists()) return;
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) throw new IOException("Cannot list " + folder);
        if (world == null) throw new IOException("Claim world is unavailable");
        for (File file : files) {
            YamlConfiguration data = read(file);
            if (!world.getUID().toString().equals(data.getString("world"))) {
                throw new IOException("Backup world does not match: " + file);
            }
            if (data.getConfigurationSection("cells") == null) {
                throw new IOException("Missing biome cells: " + file);
            }
            // Validate all biome keys before modifying this chunk.
            for (String cell : data.getConfigurationSection("cells").getKeys(false)) {
                NamespacedKey key = NamespacedKey.fromString(data.getString("cells." + cell));
                if (key == null || Registry.BIOME.get(key) == null) {
                    throw new IOException("Biome no longer registered in " + file);
                }
            }
            int chunkX = data.getInt("chunk-x");
            int chunkZ = data.getInt("chunk-z");
            world.getChunkAt(chunkX, chunkZ).load();
            for (String cell : data.getConfigurationSection("cells").getKeys(false)) {
                String[] coordinates = cell.split(",");
                Biome biome = Registry.BIOME.get(NamespacedKey.fromString(data.getString("cells." + cell)));
                world.setBiome(Integer.parseInt(coordinates[0]) << 2,
                        Integer.parseInt(coordinates[1]) << 2,
                        Integer.parseInt(coordinates[2]) << 2, biome);
            }
            world.refreshChunk(chunkX, chunkZ);
            Files.delete(file.toPath());
        }
        File[] remaining = folder.listFiles();
        if (remaining != null && remaining.length == 0) Files.delete(folder.toPath());
    }
}
