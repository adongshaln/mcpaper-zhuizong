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
    private static final double POINT_SPACING = 0.75D;
    private static final int MAX_POINTS = 160;
    private static final Component FAILED = Component.text("寻找失败", NamedTextColor.RED);

    private final Map<UUID, UUID> tracking = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public void onEnable() {
        var command = getCommand("zhuizong");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::drawBeams, 1L, 4L);
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
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("不能追踪自己。", NamedTextColor.RED));
            return true;
        }
        if (target.getUniqueId().equals(tracking.get(player.getUniqueId()))) {
            tracking.remove(player.getUniqueId());
            player.sendMessage(Component.text("已停止追踪。", NamedTextColor.GRAY));
            return true;
        }
        if (!player.getWorld().equals(target.getWorld())) {
            tracking.remove(player.getUniqueId());
            player.sendMessage(FAILED);
            return true;
        }
        tracking.put(player.getUniqueId(), target.getUniqueId());
        player.sendMessage(Component.text("正在追踪 " + target.getName() + "。", NamedTextColor.GOLD));
        drawLine(player, target);
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
                .filter(name -> !name.equals(sender.getName()))
                .toList();
    }

    private void drawBeams() {
        Iterator<Map.Entry<UUID, UUID>> entries = tracking.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, UUID> entry = entries.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Player target = Bukkit.getPlayer(entry.getValue());
            if (player == null || !player.isOnline()) {
                entries.remove();
                continue;
            }
            if (target == null || !target.isOnline() || !player.getWorld().equals(target.getWorld())) {
                entries.remove();
                player.sendMessage(FAILED);
                continue;
            }
            drawLine(player, target);
        }
    }

    private static void drawLine(Player from, Player to) {
        Location start = from.getLocation().add(0.0D, 1.0D, 0.0D);
        Location end = to.getLocation().add(0.0D, 1.0D, 0.0D);
        double dx = end.getX() - start.getX();
        double dy = end.getY() - start.getY();
        double dz = end.getZ() - start.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.5D) {
            from.getWorld().spawnParticle(Particle.FLAME, end, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            return;
        }
        int points = Math.min(MAX_POINTS, Math.max(2, (int) Math.ceil(distance / POINT_SPACING)));
        for (int index = 1; index <= points; index++) {
            double t = index / (double) points;
            from.getWorld().spawnParticle(
                    Particle.FLAME,
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
}
