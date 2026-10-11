package me.opskillstreak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class OPSkillStreak extends JavaPlugin implements Listener, TabExecutor {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String ADMIN_PERMISSION = "opskillstreak.admin";

    private static final class Stats {
        String name = "Unknown";
        int streak;
        int best;
        int kills;
        int deaths;
    }

    private final Map<UUID, Stats> stats = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> recentKills = new HashMap<>();
    private File statsFile;
    private boolean dirty;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadStats();
        getServer().getPluginManager().registerEvents(this, this);

        for (String name : new String[]{"skill", "streak", "streaktop", "opskill"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().severe("Command missing from plugin.yml: " + name);
                continue;
            }
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        long interval = 20L * 60L * 5L;
        getServer().getScheduler().runTaskTimer(this, this::saveStatsIfDirty, interval, interval);
        getLogger().info("OPSkillStreak enabled.");
    }

    @Override
    public void onDisable() {
        saveStats();
    }

    // ---------------------------------------------------------------- stats

    private void loadStats() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }
        statsFile = new File(getDataFolder(), "stats.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(statsFile);

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                ConfigurationSection section = players.getConfigurationSection(key);
                if (section == null) continue;
                try {
                    UUID id = UUID.fromString(key);
                    Stats s = new Stats();
                    s.name = section.getString("name", "Unknown");
                    s.streak = section.getInt("streak", 0);
                    s.best = Math.max(section.getInt("best", 0), s.streak);
                    s.kills = section.getInt("kills", 0);
                    s.deaths = section.getInt("deaths", 0);
                    stats.put(id, s);
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Skipping invalid UUID in stats.yml: " + key);
                }
            }
        }

        // Old 1.0.0 format: "<uuid>: <streak>"
        for (String key : yaml.getKeys(false)) {
            if (key.equals("players")) continue;
            try {
                UUID id = UUID.fromString(key);
                int old = yaml.getInt(key, 0);
                Stats s = stats.computeIfAbsent(id, ignored -> new Stats());
                s.streak = old;
                s.best = Math.max(s.best, old);
                dirty = true;
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping invalid key in stats.yml: " + key);
            }
        }
    }

    private void saveStatsIfDirty() {
        if (dirty) saveStats();
    }

    private void saveStats() {
        if (statsFile == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Stats> entry : stats.entrySet()) {
            String base = "players." + entry.getKey();
            Stats s = entry.getValue();
            yaml.set(base + ".name", s.name);
            yaml.set(base + ".streak", s.streak);
            yaml.set(base + ".best", s.best);
            yaml.set(base + ".kills", s.kills);
            yaml.set(base + ".deaths", s.deaths);
        }
        try {
            yaml.save(statsFile);
            dirty = false;
        } catch (IOException e) {
            getLogger().severe("Could not save stats.yml: " + e.getMessage());
        }
    }

    private Stats stats(Player player) {
        Stats s = stats.computeIfAbsent(player.getUniqueId(), ignored -> new Stats());
        s.name = player.getName();
        return s;
    }

    private Map.Entry<UUID, Stats> findByName(String name) {
        for (Map.Entry<UUID, Stats> entry : stats.entrySet()) {
            if (entry.getValue().name.equalsIgnoreCase(name)) return entry;
        }
        return null;
    }

    // --------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        stats(event.getPlayer());
        dirty = true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.remove(id);
        recentKills.remove(id);
        dirty = true;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Stats v = stats(victim);
        v.deaths++;
        if (v.streak >= getConfig().getInt("killstreak.announce-end-from", 3)) {
            broadcast(msg("streak-ended",
                    "%player%", victim.getName(),
                    "%streak%", String.valueOf(v.streak)));
        }
        v.streak = 0;

        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            creditKill(killer, victim);
        }
        dirty = true;
    }

    private void creditKill(Player killer, Player victim) {
        long now = System.currentTimeMillis();
        long lockMillis = getConfig().getLong("anti-exploit.same-victim-cooldown", 60L) * 1000L;
        Map<UUID, Long> mine = recentKills.computeIfAbsent(killer.getUniqueId(), ignored -> new HashMap<>());
        Long last = mine.get(victim.getUniqueId());
        if (last != null && now - last < lockMillis) {
            send(killer, msg("kill-not-counted"));
            return;
        }
        mine.put(victim.getUniqueId(), now);
        mine.values().removeIf(time -> now - time >= lockMillis);

        Stats k = stats(killer);
        k.kills++;
        k.streak++;
        if (k.streak > k.best) k.best = k.streak;

        if (k.streak >= getConfig().getInt("killstreak.announce-from", 2)) {
            broadcast(msg("streak-kill",
                    "%player%", killer.getName(),
                    "%streak%", String.valueOf(k.streak)));
        }
        giveRewards(killer, k.streak);
    }

    private void giveRewards(Player killer, int streak) {
        if (!getConfig().getBoolean("rewards.enabled", true)) return;

        int xp = getConfig().getInt("rewards.kill.xp", 0);
        if (xp > 0) killer.giveExp(xp);

        ConfigurationSection milestone = getConfig().getConfigurationSection("rewards.milestones." + streak);
        if (milestone == null) return;
        for (String command : milestone.getStringList("commands")) {
            getServer().dispatchCommand(getServer().getConsoleSender(),
                    command.replace("%player%", killer.getName()));
        }
        String message = milestone.getString("message", "");
        if (!message.isEmpty()) {
            killer.sendMessage(LEGACY.deserialize(message));
        }
    }

    // ------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "skill" -> handleSkill(sender, args);
            case "streak" -> handleStreak(sender);
            case "streaktop" -> handleTop(sender);
            case "opskill" -> handleAdmin(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleSkill(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, msg("player-only"));
            return;
        }
        if (!player.hasPermission("opskillstreak.use")) {
            send(player, msg("no-permission"));
            return;
        }
        if (args.length != 1) {
            send(player, msg("usage"));
            return;
        }
        String skill = args[0].toLowerCase(Locale.ROOT);
        if (!skill.equals("dash") && !skill.equals("thunder") && !skill.equals("shockwave")) {
            send(player, msg("usage"));
            return;
        }
        useSkill(player, skill);
    }

    private void handleStreak(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, msg("player-only"));
            return;
        }
        send(player, statsMessage(stats(player)));
    }

    private Component statsMessage(Stats s) {
        return msg("stats",
                "%streak%", String.valueOf(s.streak),
                "%best%", String.valueOf(s.best),
                "%kills%", String.valueOf(s.kills),
                "%deaths%", String.valueOf(s.deaths));
    }

    private void handleTop(CommandSender sender) {
        if (!sender.hasPermission("opskillstreak.top")) {
            send(sender, msg("no-permission"));
            return;
        }
        List<Stats> sorted = new ArrayList<>(stats.values());
        sorted.removeIf(s -> s.best <= 0 && s.kills <= 0);
        sorted.sort(Comparator.<Stats>comparingInt(s -> s.best).reversed()
                .thenComparing(Comparator.<Stats>comparingInt(s -> s.kills).reversed()));
        if (sorted.isEmpty()) {
            send(sender, msg("top-empty"));
            return;
        }
        send(sender, msg("top-header"));
        int rank = 1;
        for (Stats s : sorted) {
            if (rank > 10) break;
            send(sender, msg("top-line",
                    "%rank%", String.valueOf(rank),
                    "%player%", s.name,
                    "%best%", String.valueOf(s.best),
                    "%kills%", String.valueOf(s.kills)));
            rank++;
        }
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, msg("no-permission"));
            return;
        }
        if (args.length == 0) {
            send(sender, msg("admin-usage"));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                send(sender, msg("reload"));
            }
            case "stats" -> {
                if (args.length != 2) {
                    send(sender, msg("admin-usage"));
                    return;
                }
                Map.Entry<UUID, Stats> entry = findByName(args[1]);
                if (entry == null) {
                    send(sender, msg("unknown-player"));
                    return;
                }
                send(sender, statsMessage(entry.getValue()));
            }
            case "reset" -> {
                if (args.length != 2) {
                    send(sender, msg("admin-usage"));
                    return;
                }
                Map.Entry<UUID, Stats> entry = findByName(args[1]);
                if (entry == null) {
                    send(sender, msg("unknown-player"));
                    return;
                }
                Stats s = entry.getValue();
                s.streak = 0;
                s.best = 0;
                s.kills = 0;
                s.deaths = 0;
                dirty = true;
                send(sender, msg("admin-reset", "%player%", s.name));
            }
            case "set" -> {
                if (args.length != 3) {
                    send(sender, msg("admin-usage"));
                    return;
                }
                Map.Entry<UUID, Stats> entry = findByName(args[1]);
                if (entry == null) {
                    send(sender, msg("unknown-player"));
                    return;
                }
                int value;
                try {
                    value = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    send(sender, msg("invalid-number"));
                    return;
                }
                if (value < 0) {
                    send(sender, msg("invalid-number"));
                    return;
                }
                Stats s = entry.getValue();
                s.streak = value;
                if (value > s.best) s.best = value;
                dirty = true;
                send(sender, msg("admin-set", "%player%", s.name, "%streak%", String.valueOf(value)));
            }
            default -> send(sender, msg("admin-usage"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("skill") && args.length == 1) {
            return filter(List.of("dash", "thunder", "shockwave"), args[0]);
        }
        if (name.equals("opskill") && sender.hasPermission(ADMIN_PERMISSION)) {
            if (args.length == 1) {
                return filter(List.of("reload", "reset", "set", "stats"), args[0]);
            }
            if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
                List<String> names = new ArrayList<>();
                for (Stats s : stats.values()) names.add(s.name);
                return filter(names, args[1]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(option);
        }
        return result;
    }

    // --------------------------------------------------------------- skills

    private void useSkill(Player player, String skill) {
        if (getConfig().getStringList("disabled-worlds").contains(player.getWorld().getName())) {
            send(player, msg("world-disabled"));
            return;
        }
        if (!getConfig().getBoolean(skill + ".enabled", true)) {
            send(player, msg("skill-disabled"));
            return;
        }
        boolean bypass = player.hasPermission("opskillstreak.bypass");
        int required = getConfig().getInt(skill + ".required-streak", 0);
        int streak = stats(player).streak;
        if (!bypass && required > 0 && streak < required) {
            send(player, msg("skill-locked",
                    "%required%", String.valueOf(required),
                    "%streak%", String.valueOf(streak)));
            return;
        }
        if (!bypass && onCooldown(player, skill)) return;

        boolean used = switch (skill) {
            case "dash" -> useDash(player);
            case "thunder" -> useThunder(player);
            case "shockwave" -> useShockwave(player);
            default -> false;
        };
        if (used && !bypass) startCooldown(player, skill);
    }

    private boolean useDash(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        double horizontal = getConfig().getDouble("dash.horizontal-power", 1.65);
        double vertical = getConfig().getDouble("dash.vertical-power", 0.32);
        player.setVelocity(direction.multiply(horizontal).setY(vertical));
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 18, 0.35, 0.25, 0.35, 0.04);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.5f);
        send(player, msg("dash"));
        return true;
    }

    private boolean useThunder(Player player) {
        double range = getConfig().getDouble("thunder.range", 25.0);
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        // Do not hit entities behind walls.
        double maxDistance = range;
        RayTraceResult blockHit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        if (blockHit != null) {
            maxDistance = Math.min(range, blockHit.getHitPosition().distance(eye.toVector()));
        }

        RayTraceResult result = world.rayTraceEntities(eye, direction, maxDistance, 0.5,
                entity -> entity instanceof LivingEntity living && isValidTarget(player, living));

        if (result == null || !(result.getHitEntity() instanceof LivingEntity target)) {
            send(player, msg("thunder-no-target"));
            return false;
        }

        Location targetLocation = target.getLocation();
        if (getConfig().getBoolean("thunder.strike-lightning", true)) {
            world.strikeLightningEffect(targetLocation);
        }
        target.damage(getConfig().getDouble("thunder.damage", 8.0), player);
        world.spawnParticle(Particle.ELECTRIC_SPARK,
                targetLocation.clone().add(0, 1, 0), 28, 0.5, 0.7, 0.5, 0.1);
        world.playSound(targetLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.4f);
        send(player, msg("thunder-hit"));
        return true;
    }

    private boolean useShockwave(Player player) {
        double radius = getConfig().getDouble("shockwave.radius", 5.0);
        double damage = getConfig().getDouble("shockwave.damage", 5.0);
        double horizontal = getConfig().getDouble("shockwave.horizontal-knockback", 1.25);
        double vertical = getConfig().getDouble("shockwave.vertical-knockback", 0.45);
        Location center = player.getLocation();
        World world = player.getWorld();

        world.spawnParticle(Particle.EXPLOSION, center, 1);
        world.spawnParticle(Particle.CLOUD, center, 45, radius / 2.0, 0.25, radius / 2.0, 0.08);
        world.spawnParticle(Particle.CRIT, center, 35, radius / 2.0, 0.5, radius / 2.0, 0.12);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.3f);

        for (LivingEntity target : world.getNearbyLivingEntities(center, radius, radius, radius)) {
            if (!isValidTarget(player, target)) continue;
            if (target.getLocation().distanceSquared(center) > radius * radius) continue;

            Vector push = target.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (push.lengthSquared() < 0.001) {
                push = player.getLocation().getDirection().setY(0);
            }
            if (push.lengthSquared() < 0.001) {
                push = new Vector(1, 0, 0);
            }
            push.normalize().multiply(horizontal).setY(vertical);

            target.damage(damage, player);
            if (!target.isDead()) {
                target.setVelocity(push);
            }
        }
        send(player, msg("shockwave"));
        return true;
    }

    private boolean isValidTarget(Player user, LivingEntity target) {
        if (target.getUniqueId().equals(user.getUniqueId())) return false;
        if (target instanceof ArmorStand) return false;
        if (target.isDead() || target.isInvulnerable()) return false;
        if (target instanceof Player other) {
            GameMode mode = other.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return false;
        }
        if (target instanceof Tameable pet && pet.isTamed() && pet.getOwner() != null
                && pet.getOwner().getUniqueId().equals(user.getUniqueId())) {
            return false;
        }
        return true;
    }

    private boolean onCooldown(Player player, String skill) {
        Map<String, Long> mine = cooldowns.get(player.getUniqueId());
        if (mine == null) return false;
        long now = System.currentTimeMillis();
        long readyAt = mine.getOrDefault(skill, 0L);
        if (now >= readyAt) return false;
        long seconds = Math.max(1L, (readyAt - now + 999L) / 1000L);
        send(player, msg("cooldown", "%seconds%", String.valueOf(seconds)));
        return true;
    }

    private void startCooldown(Player player, String skill) {
        long waitMillis = getConfig().getLong("cooldowns." + skill, 5L) * 1000L;
        cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .put(skill, System.currentTimeMillis() + waitMillis);
    }

    // ------------------------------------------------------------- messages

    private Component msg(String path, String... replacements) {
        String text = getConfig().getString("messages." + path, "&cMissing message: " + path);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return LEGACY.deserialize(getConfig().getString("messages.prefix", "") + text);
    }

    private void send(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }

    private void broadcast(Component message) {
        getServer().broadcast(message);
    }
}
