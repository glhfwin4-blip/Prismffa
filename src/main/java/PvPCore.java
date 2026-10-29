package com.example.pvpcore;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PvPCore extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> kitCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> kills = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deaths = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> coins = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (String cmd : List.of("kit", "coins", "discord", "pvpcore")) {
            Objects.requireNonNull(getCommand(cmd)).setExecutor(this);
            Objects.requireNonNull(getCommand(cmd)).setTabCompleter(this);
        }
        for (Player p : Bukkit.getOnlinePlayers()) setupPlayer(p);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (getConfig().getBoolean("tab.enabled", true)) updateTab(p);
                if (getConfig().getBoolean("scoreboard.enabled", true)) updateBoard(p);
            }
        }, 20L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            combatUntil.entrySet().removeIf(e -> e.getValue() <= now);
        }, 20L, 20L);
        getLogger().info("PvPCore enabled.");
    }

    @Override public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) if (isInCombat(p) && getConfig().getBoolean("combat.combat-log-punishment", true)) p.setHealth(0);
        saveData();
    }

    private void setupPlayer(Player p) {
        String dataPath = "data." + p.getUniqueId();
        coins.putIfAbsent(p.getUniqueId(), getConfig().getInt(dataPath + ".coins", getConfig().getInt("coins.starting-balance", 0)));
        kills.putIfAbsent(p.getUniqueId(), getConfig().getInt(dataPath + ".kills", 0)); deaths.putIfAbsent(p.getUniqueId(), getConfig().getInt(dataPath + ".deaths", 0));
        if (getConfig().getBoolean("tab.enabled", true)) updateTab(p);
        if (getConfig().getBoolean("scoreboard.enabled", true)) updateBoard(p);
        if (getConfig().getBoolean("anti-vpn.enabled", false)) checkVpn(p);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { setupPlayer(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (isInCombat(p) && getConfig().getBoolean("combat.combat-log-punishment", true)) p.setHealth(0);
        combatUntil.remove(p.getUniqueId()); boards.remove(p.getUniqueId()); saveData();
    }

    @EventHandler public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!getConfig().getBoolean("combat.enabled", true)) return;
        Player attacker = null, victim = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        if (e.getEntity() instanceof Player p) victim = p;
        if (e.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player p) attacker = p;
        if (attacker != null && victim != null && attacker != victim) {
            long until = System.currentTimeMillis() + getConfig().getInt("combat.duration-seconds", 15) * 1000L;
            combatUntil.put(attacker.getUniqueId(), until); combatUntil.put(victim.getUniqueId(), until);
            attacker.sendActionBar(color("&c⚔ In combat! Don't log out."));
            victim.sendActionBar(color("&c⚔ In combat! Don't log out."));
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        deaths.merge(victim.getUniqueId(), 1, Integer::sum);
        combatUntil.remove(victim.getUniqueId());
        Player killer = victim.getKiller();
        if (killer != null) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            int reward = getConfig().getInt("coins.kill-reward", 5);
            coins.merge(killer.getUniqueId(), reward, Integer::sum);
            killer.sendMessage(color("&a+" + reward + " coins for the kill!"));
            if (getConfig().getBoolean("heal-on-kill", true)) {
                killer.setHealth(killer.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                killer.setFoodLevel(20); killer.setSaturation(20f);
                killer.sendMessage(color("&a❤ You were fully healed for that kill!"));
            }
            combatUntil.remove(killer.getUniqueId());
        }
        Bukkit.getScheduler().runTaskLater(this, () -> { if (victim.isOnline()) updateBoard(victim); if (killer != null && killer.isOnline()) updateBoard(killer); }, 1L);
    }

    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isInCombat(e.getPlayer()) || !getConfig().getBoolean("combat.prevent-commands", true)) return;
        String msg = e.getMessage().toLowerCase(Locale.ROOT).split(" ")[0];
        List<String> allowed = getConfig().getStringList("combat.allowed-commands");
        if (allowed.stream().map(s -> s.toLowerCase(Locale.ROOT)).noneMatch(msg::equals)) {
            e.setCancelled(true); e.getPlayer().sendMessage(color("&cYou cannot use that command while in combat."));
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("discord")) { sender.sendMessage(color("&bDiscord: &f" + getConfig().getString("discord-url", "https://discord.gg/yourinvite"))); return true; }
        if (name.equals("pvpcore")) {
            if (!sender.hasPermission("pvpcore.admin")) { sender.sendMessage(color("&cNo permission.")); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) { reloadConfig(); for (Player p : Bukkit.getOnlinePlayers()) setupPlayer(p); sender.sendMessage(color("&aPvPCore config reloaded.")); }
            else sender.sendMessage(color("&eUse /pvpcore reload")); return true;
        }
        if (name.equals("coins")) {
            if (args.length > 0 && sender.hasPermission("pvpcore.admin")) {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) { sender.sendMessage(color("&cPlayer must be online.")); return true; }
                sender.sendMessage(color("&e" + target.getName() + " has &6" + coins.getOrDefault(target.getUniqueId(), 0) + " coins&e.")); return true;
            }
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            sender.sendMessage(color("&eYour balance: &6" + coins.getOrDefault(p.getUniqueId(), 0) + " coins")); return true;
        }
        if (name.equals("kit")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            ConfigurationSection kits = getConfig().getConfigurationSection("kits");
            if (kits == null || kits.getKeys(false).isEmpty()) { p.sendMessage(color("&cNo kits are configured.")); return true; }
            if (args.length == 0) { p.sendMessage(color("&bAvailable kits: &f" + String.join(", ", kits.getKeys(false)))); p.sendMessage(color("&7Use /kit <name>")); return true; }
            String kit = args[0].toLowerCase(Locale.ROOT); ConfigurationSection section = kits.getConfigurationSection(kit);
            if (section == null) { p.sendMessage(color("&cThat kit does not exist.")); return true; }
            String perm = section.getString("permission", ""); if (!perm.isBlank() && !p.hasPermission(perm)) { p.sendMessage(color("&cYou don't have permission to use this kit.")); return true; }
            long now = System.currentTimeMillis(), cooldown = section.getLong("cooldown-seconds", 0) * 1000L;
            long ready = kitCooldowns.getOrDefault(p.getUniqueId().toString() + ":" + kit, 0L);
            if (ready > now) { p.sendMessage(color("&cKit cooldown: " + ((ready-now+999)/1000) + "s")); return true; }
            for (String spec : section.getStringList("items")) {
                String[] bits = spec.split(":");
                try { Material material = Material.valueOf(bits[0].toUpperCase(Locale.ROOT)); int amount = bits.length > 1 ? Integer.parseInt(bits[1]) : 1; p.getInventory().addItem(new ItemStack(material, Math.max(1, amount))); }
                catch (IllegalArgumentException ex) { getLogger().warning("Invalid kit item: " + spec); }
            }
            kitCooldowns.put(p.getUniqueId().toString() + ":" + kit, now + cooldown);
            p.sendMessage(color("&aYou claimed the &b" + kit + " &akit!")); return true;
        }
        return false;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("kit") && args.length == 1) { ConfigurationSection s = getConfig().getConfigurationSection("kits"); return s == null ? List.of() : s.getKeys(false).stream().filter(k -> k.startsWith(args[0].toLowerCase(Locale.ROOT))).toList(); }
        if (command.getName().equalsIgnoreCase("pvpcore") && args.length == 1) return List.of("reload");
        return List.of();
    }

    private boolean isInCombat(Player p) { return combatUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis(); }
    private void updateTab(Player p) {
        List<String> header = getConfig().getStringList("tab.header"), footer = getConfig().getStringList("tab.footer");
        p.sendPlayerListHeaderAndFooter(LegacyComponentSerializer.legacySection().deserialize(color(String.join("\n", header))), LegacyComponentSerializer.legacySection().deserialize(color(String.join("\n", footer))));
    }
    private void updateBoard(Player p) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("pvpcore", Criteria.DUMMY, color(getConfig().getString("scoreboard.title", "&b&lYOUR SERVER")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<String> lines = getConfig().getStringList("scoreboard.lines"); int score = lines.size(); Set<String> used = new HashSet<>();
        for (String raw : lines) {
            String line = color(raw.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size())).replace("%coins%", String.valueOf(coins.getOrDefault(p.getUniqueId(), 0))).replace("%kills%", String.valueOf(kills.getOrDefault(p.getUniqueId(), 0))).replace("%deaths%", String.valueOf(deaths.getOrDefault(p.getUniqueId(), 0))));
            if (line.isBlank()) line = " "; while (!used.add(line)) line += ChatColor.RESET;
            obj.getScore(line).setScore(score--);
        }
        p.setScoreboard(board); boards.put(p.getUniqueId(), board);
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    private void checkVpn(Player player) {
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        if (ip == null || ip.equals("127.0.0.1") || ip.contains(":")) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://ip-api.com/json/" + ip + "?fields=status,proxy,hosting")).timeout(Duration.ofSeconds(4)).GET().build();
                String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
                boolean flagged = body.contains("\"status\":\"success\"") && (body.contains("\"proxy\":true") || body.contains("\"hosting\":true"));
                if (flagged) Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) player.kickPlayer(color(getConfig().getString("anti-vpn.kick-message", "&cVPN/proxy connections are not allowed."))); });
            } catch (Exception ex) { getLogger().fine("VPN check unavailable: " + ex.getMessage()); }
        });
    }

    private void saveData() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String id = p.getUniqueId().toString(); getConfig().set("data." + id + ".coins", coins.getOrDefault(p.getUniqueId(), 0));
            getConfig().set("data." + id + ".kills", kills.getOrDefault(p.getUniqueId(), 0)); getConfig().set("data." + id + ".deaths", deaths.getOrDefault(p.getUniqueId(), 0));
        }
        try { saveConfig(); } catch (Exception ex) { getLogger().warning("Could not save player data: " + ex.getMessage()); }
    }
}
