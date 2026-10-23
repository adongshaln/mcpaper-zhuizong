package com.adongcraft.zhuizong;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ZhuizongPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final double POINT_SPACING = 2.5D;
    private static final int MAX_POINTS = 40;
    private static final int PARTICLES_PER_POINT = 6;
    private static final double PARTICLE_SPREAD = 0.22D;
    private static final double ARRIVE_DISTANCE = 3.0D;
    private static final Component FAILED = Component.text("寻找失败", NamedTextColor.RED);
    private static final Component STOPPED = Component.text("已停止追踪。", NamedTextColor.GRAY);
    private static final Component ARRIVED = Component.text("已结束追踪。", NamedTextColor.GRAY);

    private final Map<UUID, Target> tracking = new ConcurrentHashMap<>();
    private final Map<UUID, DeathSpot> deaths = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public void onEnable() {
        var command = getCommand("zhuizong");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        loadDeaths();
        Bukkit.getPluginManager().registerEvents(this, this);
        task = Bukkit.getScheduler().runTaskTimer(this, this::drawBeams, 10L, 20L);
        getLogger().info("追踪已启用");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        tracking.clear();
        saveDeaths();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("这个命令只能由玩家使用。", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(Component.text("用法：/zhuizong <玩家|死亡>", NamedTextColor.RED));
            return true;
        }
        if (isDeathWord(args[0])) {
            trackDeath(player);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(FAILED);
            return true;
        }
        Target next = new Target(target.getUniqueId(), target.getUniqueId().equals(player.getUniqueId()) ? Kind.RESPAWN : Kind.PLAYER);
        if (next.equals(tracking.get(player.getUniqueId()))) {
            tracking.remove(player.getUniqueId());
            player.sendMessage(STOPPED);
            return true;
        }
        Location destination = destination(player, next, deaths);
        if (destination == null) {
            tracking.remove(player.getUniqueId());
            player.sendMessage(FAILED);
            return true;
        }
        if (player.getLocation().distance(destination) <= ARRIVE_DISTANCE) {
            tracking.remove(player.getUniqueId());
            player.sendMessage(ARRIVED);
            return true;
        }
        tracking.put(player.getUniqueId(), next);
        player.sendMessage(Component.text(next.kind() == Kind.RESPAWN ? "正在追踪重生点。" : "正在追踪 " + target.getName() + "。", NamedTextColor.GOLD));
        drawLine(player, destination);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        if ("死亡".startsWith(args[0]) || "death".startsWith(prefix)) {
            matches.add("死亡");
        }
        matches.addAll(Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList());
        return matches;
    }

    private void drawBeams() {
        Iterator<Map.Entry<UUID, Target>> entries = tracking.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Target> entry = entries.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entries.remove();
                continue;
            }
            Location destination = destination(player, entry.getValue(), deaths);
            if (destination == null) {
                entries.remove();
                player.sendMessage(FAILED);
                continue;
            }
            if (player.getLocation().distance(destination) <= ARRIVE_DISTANCE) {
                entries.remove();
                player.sendMessage(ARRIVED);
                continue;
            }
            drawLine(player, destination);
        }
    }

    private static Location destination(Player player, Target target, Map<UUID, DeathSpot> deaths) {
        if (target.kind() == Kind.DEATH) {
            return deathLocation(deaths.get(player.getUniqueId()), player.getWorld());
        }
        if (target.kind() == Kind.RESPAWN) {
            Location respawn = player.getRespawnLocation();
            if (respawn == null || respawn.getWorld() == null) {
                respawn = player.getWorld().getSpawnLocation();
            }
            if (!player.getWorld().equals(respawn.getWorld())) {
                return null;
            }
            return respawn;
        }
        Player other = Bukkit.getPlayer(target.playerId());
        if (other == null || !other.isOnline() || !player.getWorld().equals(other.getWorld())) {
            return null;
        }
        return other.getLocation();
    }

    private static void drawLine(Player from, Location end) {
        Location start = from.getLocation().add(0.0D, 1.0D, 0.0D);
        Location aim = end.clone().add(0.0D, 1.0D, 0.0D);
        double dx = aim.getX() - start.getX();
        double dy = aim.getY() - start.getY();
        double dz = aim.getZ() - start.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.5D) {
            spawnBurst(from, aim.getX(), aim.getY(), aim.getZ());
            return;
        }
        double shown = Math.min(distance, MAX_POINTS * POINT_SPACING);
        int points = Math.max(1, (int) Math.ceil(shown / POINT_SPACING));
        for (int index = 1; index <= points; index++) {
            double travelled = Math.min(distance, index * POINT_SPACING);
            double t = travelled / distance;
            spawnBurst(from, start.getX() + dx * t, start.getY() + dy * t, start.getZ() + dz * t);
        }
    }

    private static void spawnBurst(Player viewer, double x, double y, double z) {
        viewer.spawnParticle(
                Particle.PORTAL,
                x,
                y,
                z,
                PARTICLES_PER_POINT,
                PARTICLE_SPREAD,
                PARTICLE_SPREAD,
                PARTICLE_SPREAD,
                0.0D,
                (Void) null,
                true
        );
    }

    private void trackDeath(Player player) {
        DeathSpot spot = deaths.get(player.getUniqueId());
        if (spot == null) {
            player.sendMessage(Component.text("还没有死亡位置。", NamedTextColor.RED));
            return;
        }
        Target next = new Target(player.getUniqueId(), Kind.DEATH);
        if (next.equals(tracking.get(player.getUniqueId()))) {
            tracking.remove(player.getUniqueId());
            player.sendMessage(STOPPED);
            return;
        }
        if (deathLocation(spot, player.getWorld()) == null) {
            player.sendMessage(Component.text(
                    "上一次死亡在另一个世界：" + spot.world() + " " + (int) spot.x() + " " + (int) spot.y() + " " + (int) spot.z(),
                    NamedTextColor.RED
            ));
            return;
        }
        tracking.put(player.getUniqueId(), next);
        player.sendMessage(Component.text("正在追踪上一次的死亡位置。", NamedTextColor.GOLD));
        drawLine(player, deathLocation(spot, player.getWorld()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Location location = event.getEntity().getLocation();
        if (location.getWorld() == null) {
            return;
        }
        deaths.put(event.getEntity().getUniqueId(), new DeathSpot(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ()
        ));
        saveDeaths();
    }

    private static boolean isDeathWord(String word) {
        return "死亡".equals(word) || "death".equalsIgnoreCase(word);
    }

    private static Location deathLocation(DeathSpot spot, org.bukkit.World onlyIn) {
        if (spot == null) {
            return null;
        }
        org.bukkit.World world = Bukkit.getWorld(spot.world());
        if (world == null || onlyIn == null || !onlyIn.equals(world)) {
            return null;
        }
        return new Location(world, spot.x(), spot.y(), spot.z());
    }

    private void loadDeaths() {
        File file = new File(getDataFolder(), "deaths.yml");
        if (!file.isFile()) {
            return;
        }
        var yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("deaths");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                String world = section.getString(key + ".world");
                if (world == null || world.isBlank()) {
                    continue;
                }
                deaths.put(playerId, new DeathSpot(
                        world,
                        section.getDouble(key + ".x"),
                        section.getDouble(key + ".y"),
                        section.getDouble(key + ".z")
                ));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("跳过无效的死亡位置：" + key);
            }
        }
    }

    private void saveDeaths() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, DeathSpot> entry : deaths.entrySet()) {
            String path = "deaths." + entry.getKey();
            DeathSpot spot = entry.getValue();
            yaml.set(path + ".world", spot.world());
            yaml.set(path + ".x", spot.x());
            yaml.set(path + ".y", spot.y());
            yaml.set(path + ".z", spot.z());
        }
        try {
            File file = new File(getDataFolder(), "deaths.yml");
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException exception) {
            getLogger().warning("无法保存死亡位置：" + exception.getMessage());
        }
    }

    private record Target(UUID playerId, Kind kind) {
    }

    private enum Kind {
        PLAYER,
        RESPAWN,
        DEATH
    }

    private record DeathSpot(String world, double x, double y, double z) {
    }
}
