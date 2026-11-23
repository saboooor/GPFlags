package me.ryanhamshire.GPFlags.flags;

import me.ryanhamshire.GPFlags.*;
import me.ryanhamshire.GPFlags.util.MessagingUtil;
import me.ryanhamshire.GPFlags.util.BiomeBackups;
import me.ryanhamshire.GPFlags.util.Util;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.events.ClaimDeletedEvent;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.util.logging.Level;
import java.util.List;
import java.util.Locale;

public class FlagDef_ChangeBiome extends FlagDefinition {

    private final Map<Long, Object> operations = new HashMap<>();
    private final BiomeBackups backups;

    public FlagDef_ChangeBiome(FlagManager manager, GPFlags plugin) {
        super(manager, plugin);
        backups = new BiomeBackups(plugin);
    }

    private void changeBiome(Claim claim, Biome biome) {
        Object operation = new Object();
        operations.put(claim.getID(), operation);
        Location greater = claim.getGreaterBoundaryCorner();
        Location lesser = claim.getLesserBoundaryCorner();
        int lX = lesser.getBlockX();
        int lY = lesser.getBlockY();
        int lZ = lesser.getBlockZ();
        int gX = greater.getBlockX();
        int gY = Util.getMaxHeight(greater) - 1;
        int gZ = greater.getBlockZ();
        World world = lesser.getWorld();
        int ticks = 0;
        for (int cx = lX >> 4; cx <= gX >> 4; cx++) {
            for (int cz = lZ >> 4; cz <= gZ >> 4; cz++) {
                final int chunkX = cx;
                final int chunkZ = cz;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (operations.get(claim.getID()) != operation) return;
                        world.getChunkAt(chunkX, chunkZ).load();
                        int minX = Math.max(lX, chunkX << 4);
                        int maxX = Math.min(gX, (chunkX << 4) + 15);
                        int minZ = Math.max(lZ, chunkZ << 4);
                        int maxZ = Math.min(gZ, (chunkZ << 4) + 15);
                        try {
                            backups.capture(claim.getID(), world, chunkX, chunkZ,
                                    minX, maxX, lY, gY, minZ, maxZ);
                        } catch (IOException | RuntimeException e) {
                            operations.remove(claim.getID());
                            GPFlags.getInstance().getLogger().log(Level.SEVERE,
                                    "Cannot save original biomes; change stopped for claim " + claim.getID(), e);
                            return;
                        }
                        for (int x = minX; x <= maxX; x++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                for (int y = lY; y <= gY; y++) world.setBiome(x, y, z, biome);
                            }
                        }
                        world.refreshChunk(chunkX, chunkZ);
                        if (chunkX == (gX >> 4) && chunkZ == (gZ >> 4)) {
                            operations.remove(claim.getID());
                        }
                    }
                }.runTaskLater(GPFlags.getInstance(), ticks++);
            }
        }
    }

    @SuppressWarnings("deprecation")
    public boolean changeBiome(CommandSender sender, Claim claim, String biome) {
        Biome b = Registry.BIOME.get(NamespacedKey.minecraft(biome.toLowerCase(Locale.ROOT).replace(" ", "_")));
        if (b == null) {
            MessagingUtil.sendMessage(sender, "<red>Invalid biome: <aqua>" + biome);
            return false;
        }
        World world = claim.getLesserBoundaryCorner().getWorld();
        if (world == null) {
            MessagingUtil.sendMessage(sender, "<red>World does not exist.");
            return false;
        }
        String biomeKey = b.getKey().getKey();
        if (!sender.hasPermission("gpflags.flag.changebiome." + biomeKey)) {
            MessagingUtil.sendMessage(sender, "<red>You do not have permissions for the biome <aqua>" + biomeKey + " <red>.");
            return false;
        }
        changeBiome(claim, b);
        return true;
    }

    @EventHandler
    public void onClaimDelete(ClaimDeletedEvent e) {
        onFlagUnset(e.getClaim());
    }

    @Override
    public void onFlagUnset(Claim claim) {
        if (claim == null) return;
        operations.remove(claim.getID());
        try {
            backups.restore(claim.getID(), claim.getLesserBoundaryCorner().getWorld());
        } catch (IOException | RuntimeException e) {
            GPFlags.getInstance().getLogger().log(Level.SEVERE,
                    "Cannot restore biomes for claim " + claim.getID() + "; backup retained.", e);
        }
    }

    @Override
    public String getName() {
        return "ChangeBiome";
    }

    @Override
    public SetFlagResult validateParameters(String parameters, CommandSender sender) {
        if (parameters.isEmpty()) {
            return new SetFlagResult(false, new MessageSpecifier(Messages.MessageRequired));
        }

        return new SetFlagResult(true, this.getSetMessage(parameters));
    }

    @Override
    public MessageSpecifier getSetMessage(String parameters) {
        return new MessageSpecifier(Messages.ChangeBiomeSet, parameters);
    }

    @Override
    public MessageSpecifier getUnSetMessage() {
        return new MessageSpecifier(Messages.ChangeBiomeUnset);
    }

    @Override
    public List<FlagType> getFlagType() {
        return Collections.singletonList(FlagType.CLAIM);
    }
}
