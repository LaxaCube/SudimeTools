package ch.laxacube.sudimetools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dimension lunaire : monde généré par le plugin, portail et armure obligatoire.
 * <ul>
 *     <li>La commande <code>/lune</code> va sur la Lune (et en revient), avec
 *     l'Armure Lunaire complète obligatoire pour y aller.</li>
 *     <li>Alternative : le bloc <code>sudime:moon_portal</code> posé au sol, ou
 *     l'item Portail lunaire cliqué dans le vide.</li>
 *     <li>Les mobs lunaires apparaissent autour des joueurs, avec un modèle
 *     BetterModel quand il est disponible.</li>
 * </ul>
 */
public final class SudimeMoon implements Listener {

    private static final String WORLD_NAME = "moon_dimension";
    private static final String REGOLITH = "sudime:moon_regolith";
    private static final String MOON_ROCK = "sudime:moon_rock";
    private static final String PYRITE = "sudime:pyrite_ore";
    private static final String PORTAL = "sudime:moon_portal";
    private static final String HELMET = "sudime:moon_helmet";
    private static final String CHESTPLATE = "sudime:moon_chestplate";
    private static final String LEGGINGS = "sudime:moon_leggings";
    private static final String BOOTS = "sudime:moon_boots";

    private final JavaPlugin plugin;
    private final File file;
    private final NamespacedKey returnKey;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    private World world;
    private MoonGenerator generator;

    private double spawnX = 0.5;
    private double spawnY = 100.0;
    private double spawnZ = 0.5;
    /** La Lune n'est accessible qu'aux porteurs de sudime.moon tant qu'elle est fermée. */
    private boolean open;

    private boolean mobs = true;
    private String mobModel = "blue_wizard";
    private int interval = 200;
    private int count = 2;
    private int radius = 24;
    private int maxMobs = 24;

    private boolean betterModelUnavailable;

    public SudimeMoon(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "moon.yml");
        this.returnKey = new NamespacedKey(plugin, "moon_return");
    }

    public void enable() {
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // CraftEngine charge ses blocs après les plugins : on attend qu'ils existent.
        plugin.getServer().getScheduler().runTaskLater(plugin, this::bootstrap, 200L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickMobs, interval, interval);
    }

    public void disable() {
        cooldown.clear();
    }

    // ------------------------------------------------------------------
    // Monde
    // ------------------------------------------------------------------

    /** Crée le monde dès que CraftEngine peut fournir les états des blocs lunaires. */
    private void bootstrap() {
        if (ensureWorld()) {
            buildPlatform();
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, this::bootstrap, 60L);
        }
    }

    /** @return vrai si le monde lunaire est prêt. */
    private boolean ensureWorld() {
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing != null) {
            world = existing;
        } else {
            BlockData regolith = probeBlockData(REGOLITH);
            BlockData rock = probeBlockData(MOON_ROCK);
            if (regolith == null || rock == null) {
                return false;
            }
            generator = new MoonGenerator();
            generator.blockData(regolith, rock, probeBlockData(PYRITE));
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            creator.environment(World.Environment.NORMAL);
            creator.generateStructures(false);
            creator.generator(generator);
            world = creator.createWorld();
        }
        if (world == null) {
            plugin.getLogger().warning("Dimension lunaire : impossible de créer le monde " + WORLD_NAME);
            return false;
        }
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setSpawnLocation((int) spawnX, (int) spawnY, (int) spawnZ);
        plugin.getLogger().info("Dimension lunaire prête (" + WORLD_NAME + ", surface custom : "
                + (generator == null || generator.hasCustomSurface() ? "oui" : "non") + ").");
        return true;
    }

    /**
     * Place une fois le bloc CraftEngine dans un monde chargé pour récupérer son
     * état Bukkit (instrument/note de la note block), puis restaure le bloc.
     */
    private BlockData probeBlockData(String id) {
        for (World probeWorld : Bukkit.getWorlds()) {
            try {
                Block block = probeWorld.getBlockAt(0, probeWorld.getMaxHeight() - 3, 0);
                BlockData previous = block.getBlockData().clone();
                if (!CraftEngineBlocks.place(block.getLocation(), Key.of(id), false)) {
                    continue;
                }
                BlockData resolved = block.getBlockData().clone();
                block.setBlockData(previous, false);
                plugin.getLogger().info("Bloc lunaire " + id + " → " + resolved.getAsString());
                return resolved;
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Impossible de résoudre " + id + " : " + throwable);
                return null;
            }
        }
        return null;
    }

    private void buildPlatform() {
        if (world == null) {
            return;
        }
        int cx = (int) Math.floor(spawnX);
        int cy = (int) Math.floor(spawnY);
        int cz = (int) Math.floor(spawnZ);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -6; dz <= 2; dz++) {
                place(new Location(world, cx + dx, cy - 1, cz + dz), MOON_ROCK);
                place(new Location(world, cx + dx, cy - 2, cz + dz), MOON_ROCK);
            }
        }
        // Retour : un seul bloc de portail posé devant le point d'arrivée.
        place(new Location(world, cx, cy, cz - 3), PORTAL);
        world.setSpawnLocation(cx, cy, cz);
    }

    private void place(Location location, String id) {
        try {
            CraftEngineBlocks.place(location, Key.of(id), false);
        } catch (Throwable ignored) {
            // bloc indisponible : on laisse l'emplacement tel quel
        }
    }

    private Location spawnLocation() {
        if (world == null) {
            return null;
        }
        return new Location(world, spawnX, spawnY, spawnZ, 180f, 0f);
    }

    // ------------------------------------------------------------------
    // Portail
    // ------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && PORTAL.equals(ceBlockId(event.getClickedBlock()))) {
            event.setCancelled(true);
            toggle(player);
            return;
        }
        // Le portail fonctionne aussi sans être posé : clic droit dans le vide
        // avec l'item en main.
        if (event.getAction() == Action.RIGHT_CLICK_AIR) {
            String held = SudimeTools.ceId(player.getInventory().getItemInMainHand());
            if (PORTAL.equals(held)) {
                event.setCancelled(true);
                toggle(player);
            }
        }
    }

    /** Va sur la Lune, ou revient dans l'Overworld si on y est déjà. */
    public void toggle(Player player) {
        long now = System.currentTimeMillis();
        if (cooldown.getOrDefault(player.getUniqueId(), 0L) > now) {
            return;
        }
        cooldown.put(player.getUniqueId(), now + 3000L);
        if (player.getWorld().getName().equals(WORLD_NAME)) {
            leaveMoon(player);
        } else {
            enter(player);
        }
    }

    // ------------------------------------------------------------------
    // Téléportation
    // ------------------------------------------------------------------

    /** Envoie le joueur sur la Lune (armure lunaire complète obligatoire). */
    public void enter(Player player) {
        if (!open && !player.hasPermission("sudime.moon")) {
            player.sendMessage(Component.text("La Lune n'est pas encore ouverte.",
                    NamedTextColor.RED));
            return;
        }
        String missing = missingArmor(player);
        if (missing != null) {
            player.sendMessage(Component.text("Il te faut l'Armure Lunaire complète pour passer le portail "
                    + "(manque : " + missing + ").", NamedTextColor.RED));
            return;
        }
        if (!ensureWorld()) {
            player.sendMessage(Component.text("La Lune n'est pas encore prête, réessaie dans un instant.",
                    NamedTextColor.RED));
            return;
        }
        Location spawn = spawnLocation();
        if (spawn == null) {
            player.sendMessage(Component.text("Dimension lunaire indisponible.", NamedTextColor.RED));
            return;
        }
        Location back = player.getLocation();
        player.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING,
                back.getWorld().getName() + ";" + back.getX() + ";" + back.getY() + ";" + back.getZ()
                        + ";" + back.getYaw() + ";" + back.getPitch());
        player.teleport(new Location(world, spawnX, spawnY, spawnZ + 1.5, 180f, 0f));
        player.sendActionBar(Component.text("Bienvenue sur la Lune", NamedTextColor.AQUA));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_PORTAL_TRAVEL, 0.5f, 1.4f);
    }

    private void leaveMoon(Player player) {
        String raw = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        Location target = null;
        if (raw != null) {
            String[] parts = raw.split(";");
            if (parts.length == 6) {
                World backWorld = Bukkit.getWorld(parts[0]);
                if (backWorld != null) {
                    try {
                        target = new Location(backWorld,
                                Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]),
                                Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
                    } catch (NumberFormatException ignored) {
                        target = null;
                    }
                }
            }
        }
        if (target == null) {
            target = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        player.teleport(target);
        player.sendActionBar(Component.text("Retour dans l'Overworld", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_PORTAL_TRAVEL, 0.5f, 0.8f);
    }

    private String missingArmor(Player player) {
        StringBuilder missing = new StringBuilder();
        appendIfMissing(missing, "casque", HELMET, player.getInventory().getHelmet());
        appendIfMissing(missing, "plastron", CHESTPLATE, player.getInventory().getChestplate());
        appendIfMissing(missing, "jambières", LEGGINGS, player.getInventory().getLeggings());
        appendIfMissing(missing, "bottes", BOOTS, player.getInventory().getBoots());
        return missing.isEmpty() ? null : missing.toString();
    }

    private void appendIfMissing(StringBuilder missing, String label, String expected, ItemStack worn) {
        if (expected.equals(SudimeTools.ceId(worn))) {
            return;
        }
        if (!missing.isEmpty()) {
            missing.append(", ");
        }
        missing.append(label);
    }

    /** Donne la panoplie lunaire complète (test / admin). */
    public void giveKit(Player player) {
        String[] ids = {HELMET, CHESTPLATE, LEGGINGS, BOOTS};
        for (String id : ids) {
            ItemStack item = null;
            try {
                var definition = CraftEngineItems.byId(id);
                if (definition != null) {
                    item = definition.buildBukkitItem();
                }
            } catch (Throwable ignored) {
                item = null;
            }
            if (item == null) {
                player.sendMessage(Component.text("Item introuvable : " + id, NamedTextColor.RED));
                continue;
            }
            for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        player.sendMessage(Component.text("Armure Lunaire complète donnée.", NamedTextColor.AQUA));
    }

    // ------------------------------------------------------------------
    // Mobs lunaires (BetterModel)
    // ------------------------------------------------------------------

    private void tickMobs() {
        if (!mobs || world == null) {
            return;
        }
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        if (world.getEntitiesByClasses(Zombie.class).size() >= maxMobs) {
            return;
        }
        for (Player player : players) {
            for (int i = 0; i < count; i++) {
                Location spot = findSpot(player.getLocation());
                if (spot != null) {
                    spawnMoonMob(spot);
                }
            }
        }
    }

    private Location findSpot(Location around) {
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = Math.random() * Math.PI * 2;
            double distance = 8 + Math.random() * (radius - 8);
            int x = around.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = around.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
            Block ground = world.getBlockAt(x, y - 1, z);
            String id = ceBlockId(ground);
            if (!REGOLITH.equals(id) && !MOON_ROCK.equals(id) && !PYRITE.equals(id)) {
                continue;
            }
            if (!world.getBlockAt(x, y, z).getType().isAir()
                    || !world.getBlockAt(x, y + 1, z).getType().isAir()) {
                continue;
            }
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private void spawnMoonMob(Location spot) {
        try {
            Zombie zombie = world.spawn(spot, Zombie.class, entity -> {
                entity.customName(Component.text("Rôdeur Lunaire", NamedTextColor.LIGHT_PURPLE));
                entity.setCustomNameVisible(true);
                entity.setShouldBurnInDay(false);
                entity.setCanPickupItems(false);
                entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(30.0);
                entity.setHealth(30.0);
                entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.26);
                entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(6.0);
            });
            applyModel(zombie);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Spawn de mob lunaire impossible : " + throwable);
        }
    }

    /** Applique le modèle BetterModel (API appelée par réflexion, plugin optionnel). */
    private void applyModel(Entity entity) {
        if (betterModelUnavailable || mobModel == null || mobModel.isBlank()) {
            return;
        }
        try {
            Class<?> api = Class.forName("kr.toxicity.model.api.BetterModel");
            Object result = api.getMethod("modelOrNull", String.class).invoke(null, mobModel);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                betterModelUnavailable = true;
                plugin.getLogger().warning("BetterModel : modèle absent (" + mobModel + ")");
                return;
            }
            Object renderer = optional.get();
            try {
                renderer.getClass().getMethod("getOrCreate", Entity.class).invoke(renderer, entity);
            } catch (NoSuchMethodException notFound) {
                renderer.getClass().getMethod("create", Entity.class).invoke(renderer, entity);
            }
        } catch (Throwable throwable) {
            betterModelUnavailable = true;
            plugin.getLogger().warning("BetterModel indisponible : " + throwable);
        }
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    private static String ceBlockId(Block block) {
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty()) {
                return null;
            }
            return state.owner().value().id().asString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void loadConfig() {
        if (!file.exists()) {
            YamlConfiguration created = new YamlConfiguration();
            created.set("spawn.x", spawnX);
            created.set("spawn.y", spawnY);
            created.set("spawn.z", spawnZ);
            created.set("open", open);
            created.set("mobs.enabled", mobs);
            created.set("mobs.model", mobModel);
            created.set("mobs.interval", interval);
            created.set("mobs.count", count);
            created.set("mobs.radius", radius);
            created.set("mobs.max", maxMobs);
            try {
                created.save(file);
            } catch (Exception exception) {
                plugin.getLogger().warning("moon.yml non enregistré : " + exception);
            }
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        spawnX = yaml.getDouble("spawn.x", spawnX);
        spawnY = yaml.getDouble("spawn.y", spawnY);
        spawnZ = yaml.getDouble("spawn.z", spawnZ);
        open = yaml.getBoolean("open", open);
        mobs = yaml.getBoolean("mobs.enabled", mobs);
        mobModel = yaml.getString("mobs.model", mobModel);
        interval = Math.max(20, yaml.getInt("mobs.interval", interval));
        count = Math.max(1, yaml.getInt("mobs.count", count));
        radius = Math.max(8, yaml.getInt("mobs.radius", radius));
        maxMobs = Math.max(1, yaml.getInt("mobs.max", maxMobs));
    }
}
