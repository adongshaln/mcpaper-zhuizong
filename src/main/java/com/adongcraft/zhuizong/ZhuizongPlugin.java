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
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ZhuizongPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final double POINT_SPACING = 2.5D;
    private static final int MAX_POINTS = 40;
    private static final double ARRIVE_DISTANCE = 3.0D;
    private static final Component FAILED = Component.text("寻找失败", NamedTextColor.RED);
    private static final Component STOPPED = Component.text("已停止追踪。", NamedTextColor.GRAY);
    private static final Component ARRIVED = Component.text("已结束追踪。", NamedTextColor.GRAY);

    private final Map<UUID, Target> tracking = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public void onEnable() {
        var command = getCommand("zhuizong");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::drawBeams, 10L, 20L);
        getLogger().info("追踪已启用");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        tracking.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("这个命令只能由玩家使用。", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(Component.text("用法：/zhuizong <玩家>", NamedTextColor.RED));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(FAILED);
            return true;
        }
        boolean toRespawn = target.getUniqueId().equals(player.getUniqueId());
        Target next = new Target(target.getUniqueId(), toRespawn);
        if (next.equals(tracking.get(player.getUniqueId()))) {
            tracking.remove(player.getUniqueId());
            player.sendMessage(STOPPED);
            return true;
        }
        Location destination = destination(player, next);
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
        player.sendMessage(Component.text(toRespawn ? "正在追踪重生点。" : "正在追踪 " + target.getName() + "。", NamedTextColor.GOLD));
        drawLine(player, destination);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
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
            Location destination = destination(player, entry.getValue());
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

    private static Location destination(Player player, Target target) {
        if (target.respawn()) {
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
            from.getWorld().spawnParticle(Particle.PORTAL, aim, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            return;
        }
        int points = Math.min(MAX_POINTS, Math.max(2, (int) Math.ceil(distance / POINT_SPACING)));
        for (int index = 1; index <= points; index++) {
            double t = index / (double) points;
            from.getWorld().spawnParticle(
                    Particle.PORTAL,
                    start.getX() + dx * t,
                    start.getY() + dy * t,
                    start.getZ() + dz * t,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D
            );
        }
    }

    private record Target(UUID playerId, boolean respawn) {
    }
}
