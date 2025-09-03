package dev.aari.superhomes;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SuperHomes extends JavaPlugin implements Listener, CommandExecutor {

    private HikariDataSource dataSource;
    private final Map<UUID, Map<String, Location>> onlinePlayerHomes = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> teleportTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClickTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastClickedHome = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerPages = new ConcurrentHashMap<>();
    private final Map<UUID, Inventory> cachedGUIs = new ConcurrentHashMap<>();
    private final Set<UUID> onlinePlayerUUIDs = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupDatabase();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("sethome").setExecutor(this);
        getCommand("homes").setExecutor(this);
        getCommand("home").setExecutor(this);
        getCommand("delhome").setExecutor(this);
        getCommand("ahomesee").setExecutor(this);
        getCommand("ahome").setExecutor(this);

        startCleanupTask();
        displayStartupMessage();
    }

    @Override
    public void onDisable() {
        for (BukkitRunnable task : teleportTasks.values()) {
            task.cancel();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void displayStartupMessage() {
        String version = getDescription().getVersion();
        String os = System.getProperty("os.name");

        getLogger().info("§b   _____                  ________________________ ___________  .___________    _________");
        getLogger().info("§b  /  *  \\      .*_       /   _____/\\__    ___/    |   \\______ \\ |   \\_____  \\  /   _____/");
        getLogger().info("§b /  /_\\  \\   **|  |**_   \\_____  \\   |    |  |    |   /|    |  \\|   |/   |   \\ \\_____  \\ ");
        getLogger().info("§b/    |    \\ /__    __/   /        \\  |    |  |    |  / |    `   \\   /    |    \\/        \\");
        getLogger().info("§b\\____|__  /    |__|     /_______  /  |____|  |______/ /_______  /___\\_______  /_______  /");
        getLogger().info("§b        \\/                      \\/                            \\/            \\/        \\/");
        getLogger().info("§b");
        getLogger().info("§bA+ Studios , we bring the quality to you!");
        getLogger().info("§bVersion: §c" + version);
        getLogger().info("§bOperating System: §c" + os);
    }

    private void setupDatabase() {
        try {
            FileConfiguration config = getConfig();
            HikariConfig hikariConfig = new HikariConfig();

            if (config.getBoolean("database.mysql.enabled", false)) {
                String host = config.getString("database.mysql.host", "localhost");
                int port = config.getInt("database.mysql.port", 3306);
                String database = config.getString("database.mysql.database", "minecraft");
                String username = config.getString("database.mysql.username", "root");
                String password = config.getString("database.mysql.password", "");

                hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8");
                hikariConfig.setUsername(username);
                hikariConfig.setPassword(password);
                hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            } else {
                File dbFile = new File(getDataFolder(), "homes.db");
                hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                hikariConfig.setDriverClassName("org.sqlite.JDBC");
            }

            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setLeakDetectionThreshold(60000);

            dataSource = new HikariDataSource(hikariConfig);
            createTables();
        } catch (Exception e) {
            getLogger().severe("Failed to setup database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void createTables() {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean isMySQL = connection.getMetaData().getDatabaseProductName().equals("MySQL");

                String sql = "CREATE TABLE IF NOT EXISTS homes (" +
                        "id INTEGER PRIMARY KEY " + (isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT") + ", " +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "home_name VARCHAR(50) NOT NULL, " +
                        "world VARCHAR(50) NOT NULL, " +
                        "x DOUBLE NOT NULL, " +
                        "y DOUBLE NOT NULL, " +
                        "z DOUBLE NOT NULL, " +
                        "yaw FLOAT NOT NULL, " +
                        "pitch FLOAT NOT NULL, " +
                        "UNIQUE(player_uuid, home_name)" +
                        ")";

                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(sql);

                    try {
                        stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_uuid ON homes(player_uuid)");
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException e) {
                getLogger().severe("Failed to create tables: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<Map<String, Location>> loadPlayerHomes(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Location> homes = new HashMap<>();
            try (Connection connection = dataSource.getConnection()) {
                String sql = "SELECT * FROM homes WHERE player_uuid = ?";
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String homeName = rs.getString("home_name");
                            String world = rs.getString("world");
                            double x = rs.getDouble("x");
                            double y = rs.getDouble("y");
                            double z = rs.getDouble("z");
                            float yaw = rs.getFloat("yaw");
                            float pitch = rs.getFloat("pitch");

                            Location location = new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
                            homes.put(homeName, location);
                        }
                    }
                }
            } catch (SQLException e) {
                getLogger().severe("Failed to load homes for " + playerUuid + ": " + e.getMessage());
            }
            return homes;
        });
    }

    private CompletableFuture<Void> saveHomeAsync(UUID playerUuid, String homeName, Location location) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean isMySQL = connection.getMetaData().getDatabaseProductName().equals("MySQL");
                String sql;

                if (isMySQL) {
                    sql = "INSERT INTO homes (player_uuid, home_name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), yaw=VALUES(yaw), pitch=VALUES(pitch)";
                } else {
                    sql = "INSERT OR REPLACE INTO homes (player_uuid, home_name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                }

                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, homeName);
                    stmt.setString(3, location.getWorld().getName());
                    stmt.setDouble(4, location.getX());
                    stmt.setDouble(5, location.getY());
                    stmt.setDouble(6, location.getZ());
                    stmt.setFloat(7, location.getYaw());
                    stmt.setFloat(8, location.getPitch());
                    stmt.executeUpdate();
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    onlinePlayerHomes.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>()).put(homeName, location);
                    cachedGUIs.remove(playerUuid);
                });
            } catch (SQLException e) {
                getLogger().severe("Failed to save home: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<Void> deleteHomeAsync(UUID playerUuid, String homeName) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                String sql = "DELETE FROM homes WHERE player_uuid = ? AND home_name = ?";
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, homeName);
                    stmt.executeUpdate();
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    Map<String, Location> homes = onlinePlayerHomes.get(playerUuid);
                    if (homes != null) {
                        homes.remove(homeName);
                        if (homes.isEmpty()) {
                            onlinePlayerHomes.remove(playerUuid);
                        }
                    }
                    cachedGUIs.remove(playerUuid);
                });
            } catch (SQLException e) {
                getLogger().severe("Failed to delete home: " + e.getMessage());
            }
        });
    }

    private CompletableFuture<Void> renameHomeAsync(UUID playerUuid, String oldName, String newName) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                String sql = "UPDATE homes SET home_name = ? WHERE player_uuid = ? AND home_name = ?";
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, newName);
                    stmt.setString(2, playerUuid.toString());
                    stmt.setString(3, oldName);
                    stmt.executeUpdate();
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    Map<String, Location> homes = onlinePlayerHomes.get(playerUuid);
                    if (homes != null && homes.containsKey(oldName)) {
                        Location location = homes.remove(oldName);
                        homes.put(newName, location);
                    }
                    cachedGUIs.remove(playerUuid);
                });
            } catch (SQLException e) {
                getLogger().severe("Failed to rename home: " + e.getMessage());
            }
        });
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                onlinePlayerUUIDs.clear();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    onlinePlayerUUIDs.add(player.getUniqueId());
                }

                onlinePlayerHomes.keySet().retainAll(onlinePlayerUUIDs);
                lastClickTime.keySet().retainAll(onlinePlayerUUIDs);
                lastClickedHome.keySet().retainAll(onlinePlayerUUIDs);
                playerPages.keySet().retainAll(onlinePlayerUUIDs);
                cachedGUIs.keySet().retainAll(onlinePlayerUUIDs);

                teleportTasks.entrySet().removeIf(entry -> {
                    if (!onlinePlayerUUIDs.contains(entry.getKey())) {
                        entry.getValue().cancel();
                        return true;
                    }
                    return false;
                });

                getLogger().info("Cleanup complete: " + onlinePlayerHomes.size() + " players cached");
            }
        }.runTaskTimerAsynchronously(this, 1200L, 1200L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        onlinePlayerUUIDs.add(uuid);

        loadPlayerHomes(uuid).thenAccept(homes -> {
            if (!homes.isEmpty()) {
                onlinePlayerHomes.put(uuid, new ConcurrentHashMap<>(homes));
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        onlinePlayerUUIDs.remove(uuid);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!onlinePlayerUUIDs.contains(uuid)) {
                cleanupPlayerData(uuid);
            }
        }, 100L);
    }

    private void cleanupPlayerData(UUID uuid) {
        onlinePlayerHomes.remove(uuid);
        cachedGUIs.remove(uuid);
        playerPages.remove(uuid);
        lastClickTime.remove(uuid);
        lastClickedHome.remove(uuid);

        BukkitRunnable task = teleportTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getConfig().getString("messages.only-players", "§cOnly players can use this command!"));
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "sethome":
                return handleSetHome(player, args);
            case "homes":
            case "home":
                if (args.length == 0) {
                    openHomesGUI(player, 1);
                } else if (args[0].equalsIgnoreCase("rename") && args.length == 3) {
                    return handleRenameHome(player, args[1], args[2]);
                } else if (args[0].equalsIgnoreCase("delete") && args.length == 2) {
                    return handleDeleteHome(player, args[1]);
                }
                return true;
            case "delhome":
                if (args.length == 1) {
                    return handleDeleteHome(player, args[0]);
                }
                return true;
            case "ahomesee":
                if (!player.hasPermission("superhomes.admin")) {
                    player.sendMessage(getConfig().getString("messages.no-permission", "§cYou don't have permission!"));
                    return true;
                }
                if (args.length == 1) {
                    Player target = Bukkit.getPlayer(args[0]);
                    if (target != null) {
                        openAdminHomesGUI(player, target, 1);
                    } else {
                        player.sendMessage(getConfig().getString("messages.player-not-found", "§cPlayer not found!"));
                    }
                }
                return true;
            case "ahome":
                if (!player.hasPermission("superhomes.admin")) {
                    player.sendMessage(getConfig().getString("messages.no-permission", "§cYou don't have permission!"));
                    return true;
                }
                return handleAdminCommands(player, args);
        }

        return false;
    }

    private boolean handleSetHome(Player player, String[] args) {
        String homeName = args.length > 0 ? args[0] : "home";

        int maxHomes = getMaxHomes(player);
        Map<String, Location> homes = onlinePlayerHomes.get(player.getUniqueId());

        if (homes != null && homes.size() >= maxHomes && !homes.containsKey(homeName)) {
            player.sendMessage(getConfig().getString("messages.max-homes-reached", "§cYou've reached the maximum number of homes! Current limit: " + maxHomes));
            return true;
        }

        saveHomeAsync(player.getUniqueId(), homeName, player.getLocation());
        player.sendMessage(getConfig().getString("messages.home-set", "§aHome set successfully!").replace("{home}", homeName));
        return true;
    }

    private int getMaxHomes(Player player) {
        if (player.hasPermission("superhomes.homes.7")) return 700;
        if (player.hasPermission("superhomes.homes.6")) return 600;
        if (player.hasPermission("superhomes.homes.5")) return 500;
        if (player.hasPermission("superhomes.homes.4")) return 400;
        return getConfig().getInt("default-homes", 3) * 100;
    }

    private boolean canAccessPage(Player player, int page) {
        if (page <= 1) return true;

        int maxPages = getMaxHomes(player) / 7;
        if (page > maxPages) return false;

        if (page <= 43) return player.hasPermission("superhomes.page." + page) || player.hasPermission("superhomes.page.*");

        return player.hasPermission("superhomes.page.*") || player.hasPermission("superhomes.admin");
    }

    private boolean handleDeleteHome(Player player, String homeName) {
        Map<String, Location> homes = onlinePlayerHomes.get(player.getUniqueId());
        if (homes == null || !homes.containsKey(homeName)) {
            player.sendMessage(getConfig().getString("messages.home-not-found", "§cHome not found!"));
            return true;
        }

        deleteHomeAsync(player.getUniqueId(), homeName);
        player.sendMessage(getConfig().getString("messages.home-deleted", "§aHome deleted successfully!").replace("{home}", homeName));
        return true;
    }

    private boolean handleRenameHome(Player player, String oldName, String newName) {
        Map<String, Location> homes = onlinePlayerHomes.get(player.getUniqueId());
        if (homes == null || !homes.containsKey(oldName)) {
            player.sendMessage(getConfig().getString("messages.home-not-found", "§cHome not found!"));
            return true;
        }

        if (homes.containsKey(newName)) {
            player.sendMessage(getConfig().getString("messages.home-already-exists", "§cA home with that name already exists!"));
            return true;
        }

        renameHomeAsync(player.getUniqueId(), oldName, newName);
        player.sendMessage(getConfig().getString("messages.home-renamed", "§aHome renamed successfully!").replace("{old}", oldName).replace("{new}", newName));
        return true;
    }

    private boolean handleAdminCommands(Player player, String[] args) {
        if (args.length < 3) return false;

        String subCommand = args[0];
        String targetName = args[1];
        String homeName = args[2];

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            player.sendMessage(getConfig().getString("messages.player-not-found", "§cPlayer not found!"));
            return true;
        }

        switch (subCommand.toLowerCase()) {
            case "otp":
                Map<String, Location> homes = onlinePlayerHomes.get(target.getUniqueId());
                if (homes != null && homes.containsKey(homeName)) {
                    player.teleport(homes.get(homeName));
                    player.sendMessage("§aTeleported to " + targetName + "'s home: " + homeName);
                } else {
                    player.sendMessage(getConfig().getString("messages.home-not-found", "§cHome not found!"));
                }
                return true;
            case "del":
                deleteHomeAsync(target.getUniqueId(), homeName);
                player.sendMessage("§aDeleted " + targetName + "'s home: " + homeName);
                return true;
            case "rename":
                if (args.length == 4) {
                    renameHomeAsync(target.getUniqueId(), homeName, args[3]);
                    player.sendMessage("§aRenamed " + targetName + "'s home from " + homeName + " to " + args[3]);
                }
                return true;
        }

        return false;
    }

    private void openHomesGUI(Player player, int page) {
        if (!canAccessPage(player, page)) {
            player.sendMessage(getConfig().getString("messages.no-page-permission", "§cYou don't have permission to access page " + page + "!"));
            return;
        }

        playerPages.put(player.getUniqueId(), page);

        Inventory gui = cachedGUIs.get(player.getUniqueId());
        if (gui == null) {
            gui = createHomesGUI(player, page);
            cachedGUIs.put(player.getUniqueId(), gui);
        } else {
            updateHomesGUI(gui, player, page);
        }

        player.openInventory(gui);
    }

    private Inventory createHomesGUI(Player player, int page) {
        Inventory gui = Bukkit.createInventory(null, 54, "§fHomes Page " + page);
        updateHomesGUI(gui, player, page);
        return gui;
    }

    private void updateHomesGUI(Inventory gui, Player player, int page) {
        gui.clear();

        int[] bedSlots = {11, 13, 15, 28, 30, 32, 34};
        int[] dyeSlots = {20, 22, 24, 37, 39, 41, 43};

        Map<String, Location> homes = onlinePlayerHomes.get(player.getUniqueId());
        List<String> homeNames = homes != null ? new ArrayList<>(homes.keySet()) : new ArrayList<>();

        int startIndex = (page - 1) * 7;

        for (int i = 0; i < 7; i++) {
            int homeIndex = startIndex + i;
            boolean hasHome = homeIndex < homeNames.size();

            ItemStack bed = new ItemStack(hasHome ? Material.LIME_BED : Material.LIGHT_GRAY_BED);
            ItemMeta bedMeta = bed.getItemMeta();
            if (hasHome) {
                bedMeta.setDisplayName("§a" + homeNames.get(homeIndex));
                bedMeta.setLore(Arrays.asList("§7Click to teleport", "§7Home #" + (homeIndex + 1)));
            } else {
                bedMeta.setDisplayName("§7Empty Slot #" + (homeIndex + 1));
                bedMeta.setLore(Arrays.asList("§7Page: " + page + " | Slot: " + (i + 1)));
            }
            bed.setItemMeta(bedMeta);
            gui.setItem(bedSlots[i], bed);

            ItemStack dye = new ItemStack(hasHome ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta dyeMeta = dye.getItemMeta();
            dyeMeta.setDisplayName(hasHome ? "§aDelete Home" : "§7Set Home");
            dyeMeta.setLore(Arrays.asList(hasHome ? "§7Double click to delete" : "§7Click to set home here"));
            dye.setItemMeta(dyeMeta);
            gui.setItem(dyeSlots[i], dye);
        }

        if (page > 1) {
            ItemStack prevArrow = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevArrow.getItemMeta();
            prevMeta.setDisplayName("§c§o§lPREVIOUS PAGE");
            prevMeta.setLore(Arrays.asList("§7Current: Page " + page, "§7Go to: Page " + (page - 1)));
            prevArrow.setItemMeta(prevMeta);
            gui.setItem(45, prevArrow);
        }

        int maxPossibleHomes = getMaxHomes(player);
        int maxPages = (maxPossibleHomes + 6) / 7;

        if (page < maxPages && page < 100) {
            ItemStack nextArrow = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextArrow.getItemMeta();
            nextMeta.setDisplayName("§a§o§lNEXT PAGE");
            nextMeta.setLore(Arrays.asList("§7Current: Page " + page, "§7Go to: Page " + (page + 1), "§7Max Pages: " + Math.min(maxPages, 100)));
            nextArrow.setItemMeta(nextMeta);
            gui.setItem(53, nextArrow);
        }
    }

    private void openAdminHomesGUI(Player admin, Player target, int page) {
        Inventory gui = Bukkit.createInventory(null, 54, "§f" + target.getName() + "'s Homes Page " + page);

        int[] bedSlots = {11, 13, 15, 28, 30, 32, 34};
        int[] dyeSlots = {20, 22, 24, 37, 39, 41, 43};

        Map<String, Location> homes = onlinePlayerHomes.get(target.getUniqueId());
        List<String> homeNames = homes != null ? new ArrayList<>(homes.keySet()) : new ArrayList<>();

        int startIndex = (page - 1) * 7;

        for (int i = 0; i < 7; i++) {
            int homeIndex = startIndex + i;
            boolean hasHome = homeIndex < homeNames.size();

            ItemStack bed = new ItemStack(hasHome ? Material.LIME_BED : Material.LIGHT_GRAY_BED);
            ItemMeta bedMeta = bed.getItemMeta();
            if (hasHome) {
                bedMeta.setDisplayName("§a" + homeNames.get(homeIndex));
                bedMeta.setLore(Arrays.asList("§7Click to teleport"));
            } else {
                bedMeta.setDisplayName("§7Empty Slot");
            }
            bed.setItemMeta(bedMeta);
            gui.setItem(bedSlots[i], bed);

            ItemStack dye = new ItemStack(hasHome ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta dyeMeta = dye.getItemMeta();
            dyeMeta.setDisplayName(hasHome ? "§aDelete Home" : "§7No Home");
            if (hasHome) {
                dyeMeta.setLore(Arrays.asList("§7Click to delete"));
            }
            dye.setItemMeta(dyeMeta);
            gui.setItem(dyeSlots[i], dye);
        }

        admin.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (!event.getView().getTitle().contains("Homes Page")) return;

        event.setCancelled(true);

        int slot = event.getSlot();
        ItemStack item = event.getCurrentItem();
        if (item == null) return;

        int[] bedSlots = {11, 13, 15, 28, 30, 32, 34};
        int[] dyeSlots = {20, 22, 24, 37, 39, 41, 43};

        int page = playerPages.getOrDefault(player.getUniqueId(), 1);

        if (slot == 45 && page > 1) {
            openHomesGUI(player, page - 1);
            return;
        }

        if (slot == 53) {
            int maxPossibleHomes = getMaxHomes(player);
            int maxPages = (maxPossibleHomes + 6) / 7;
            int nextPage = page + 1;

            if (nextPage <= Math.min(maxPages, 100) && canAccessPage(player, nextPage)) {
                openHomesGUI(player, nextPage);
            } else if (!canAccessPage(player, nextPage)) {
                player.sendMessage(getConfig().getString("messages.no-page-permission", "§cYou don't have permission to access page " + nextPage + "!"));
            }
            return;
        }

        for (int i = 0; i < bedSlots.length; i++) {
            if (slot == bedSlots[i]) {
                handleBedClick(player, page, i);
                return;
            }
            if (slot == dyeSlots[i]) {
                handleDyeClick(player, page, i, event.getClick());
                return;
            }
        }
    }

    private void handleBedClick(Player player, int page, int slotIndex) {
        Map<String, Location> homes = onlinePlayerHomes.get(player.getUniqueId());
        if (homes == null) return;

        List<String> homeNames = new ArrayList<>(homes.keySet());
        int homeIndex = (page - 1) * 7 + slotIndex;

        if (homeIndex >= homeNames.size()) return;

        String homeName = homeNames.get(homeIndex);
        Location homeLocation = homes.get(homeName);

        startTeleport(player, homeLocation);
    }

    private void handleDyeClick(Player player, int page, int slotIndex, ClickType clickType) {
        Map<String, Location> homes = onlinePlayerHomes.get(player.getUniqueId());
        List<String> homeNames = homes != null ? new ArrayList<>(homes.keySet()) : new ArrayList<>();
        int homeIndex = (page - 1) * 7 + slotIndex;

        if (homeIndex >= homeNames.size()) {
            int maxHomes = getMaxHomes(player);
            if (homeIndex >= maxHomes) {
                player.sendMessage(getConfig().getString("messages.max-homes-reached", "§cYou've reached the maximum number of homes! Current limit: " + maxHomes));
                return;
            }

            player.closeInventory();
            player.performCommand("sethome home" + (homeIndex + 1));
            return;
        }

        String homeName = homeNames.get(homeIndex);
        long currentTime = System.currentTimeMillis();
        long lastClick = lastClickTime.getOrDefault(player.getUniqueId(), 0L);
        String lastHome = lastClickedHome.get(player.getUniqueId());

        if (currentTime - lastClick < 1000 && homeName.equals(lastHome)) {
            deleteHomeAsync(player.getUniqueId(), homeName);
            player.sendMessage(getConfig().getString("messages.home-deleted", "§aHome deleted successfully!").replace("{home}", homeName));
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(this, () -> openHomesGUI(player, page), 1L);
        } else {
            lastClickTime.put(player.getUniqueId(), currentTime);
            lastClickedHome.put(player.getUniqueId(), homeName);
        }
    }

    private void startTeleport(Player player, Location location) {
        if (teleportTasks.containsKey(player.getUniqueId())) {
            teleportTasks.get(player.getUniqueId()).cancel();
        }

        player.closeInventory();

        BukkitRunnable task = new BukkitRunnable() {
            int seconds = 5;

            @Override
            public void run() {
                if (seconds <= 0) {
                    player.teleport(location);
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    teleportTasks.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                String message = getConfig().getString("messages.teleporting", "§e§lGOLDVANILLA ▪§r§aTeleporting in §c{seconds}s")
                        .replace("{seconds}", String.format("%.1f", seconds + 0.0));

                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));

                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 0.5f, 1.0f);
                seconds--;
            }
        };

        teleportTasks.put(player.getUniqueId(), task);
        task.runTaskTimer(this, 0L, 20L);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!teleportTasks.containsKey(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to != null && (from.getX() != to.getX() || from.getZ() != to.getZ() ||
                Math.abs(from.getY() - to.getY()) > 0.1)) {

            teleportTasks.get(player.getUniqueId()).cancel();
            teleportTasks.remove(player.getUniqueId());

            String message = getConfig().getString("messages.teleport-cancelled", "§e§lGOLDVANILLA ▪§r§cTeleport canceled, you moved!");
            player.sendMessage(message);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 1.0f, 1.0f);
        }
    }
}
