package ru.laser;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LaserPlugin extends JavaPlugin implements Listener {

    private NamespacedKey laserKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private double damage;
    private double range;
    private long cooldownMs;
    private int fireTicks;
    private Particle.DustOptions dust;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        laserKey = new NamespacedKey(this, "laser_gun");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LaserPlugin включён!");
    }

    private void loadSettings() {
        reloadConfig();
        damage = getConfig().getDouble("damage", 8.0);
        range = getConfig().getDouble("range", 50.0);
        cooldownMs = getConfig().getLong("cooldown-ticks", 10) * 50L;
        fireTicks = getConfig().getInt("fire-ticks", 40);
        int r = getConfig().getInt("color.r", 255);
        int g = getConfig().getInt("color.g", 0);
        int b = getConfig().getInt("color.b", 0);
        dust = new Particle.DustOptions(Color.fromRGB(r, g, b), 1.0f);
    }

    private ItemStack createLaser() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Лазерная пушка", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                Component.text("ПКМ — выстрел лучом", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(laserKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isLaser(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(laserKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!isLaser(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);

        if (!p.hasPermission("laser.use")) return;

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) return;
        cooldowns.put(p.getUniqueId(), now);

        shoot(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldowns.remove(e.getPlayer().getUniqueId());
    }

    private void shoot(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        RayTraceResult result = p.getWorld().rayTrace(
                eye, dir, range,
                FluidCollisionMode.NEVER,
                true,   // игнорировать проходимые блоки (трава и т.п.)
                0.2,
                entity -> entity instanceof LivingEntity && !entity.equals(p));

        double length = range;
        if (result != null) {
            length = result.getHitPosition().distance(eye.toVector());
        }

        // Рисуем луч
        for (double d = 0.8; d < length; d += 0.25) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            p.getWorld().spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }

        // Эффект попадания
        if (result != null) {
            Location hit = result.getHitPosition().toLocation(p.getWorld());
            p.getWorld().spawnParticle(Particle.FLASH, hit, 1, 0, 0, 0, 0, Color.RED);
            p.getWorld().spawnParticle(Particle.CRIT, hit, 10, 0.2, 0.2, 0.2, 0.1);

            if (result.getHitEntity() instanceof LivingEntity target) {
                target.damage(damage, p);
                if (fireTicks > 0) target.setFireTicks(fireTicks);
            }
        }

        p.getWorld().playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 2.0f);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("laser.give")) {
                sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            sender.sendMessage(Component.text("Конфиг перезагружен.", NamedTextColor.GREEN));
            return true;
        }

        if (!sender.hasPermission("laser.give")) {
            sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Укажи игрока: /laser give <ник>", NamedTextColor.RED));
            return true;
        }

        target.getInventory().addItem(createLaser());
        sender.sendMessage(Component.text("Лазерная пушка выдана: " + target.getName(), NamedTextColor.GREEN));
        return true;
    }
}
