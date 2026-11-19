package com.jerinex.botpingspoof;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class BotPingSpoofPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String HANDLER_NAME = "bot_ping_spoof_handler";

    private final Map<UUID, Integer> customBasePings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> currentLivePings = new ConcurrentHashMap<>();

    private Method craftPlayerGetHandle;
    private Field connectionField;
    private Field networkManagerField;
    private Field channelField;

    private Class<?> packetClass;
    private Field entriesField;
    private Class<?> entryClass;
    private Constructor<?> entryConstructor;
    private Field entryProfileIdField;
    private Field entryProfileField;
    private Field entryListedField;
    private Field entryLatencyField;
    private Field entryGameModeField;
    private Field entryDisplayNameField;
    private Field entryShowHatField;
    private Field entryListOrderField;
    private Field entryChatSessionField;

    private Object updateLatencyEnumConstant;
    private Constructor<?> packetConstructor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!initReflection()) {
            getLogger().severe("Failed to initialize packet reflection for this Paper build. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("botping")).setExecutor(this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            injectPlayer(player);
        }

        long interval = getConfig().getLong("update-interval-ticks", 60L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickPingJitter, 20L, interval);
        getLogger().info("BotPingSpoof enabled! 0ms bots will now display realistic ping.");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            uninjectPlayer(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        injectPlayer(player);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && shouldSpoof(player)) {
                assignNewJitter(player.getUniqueId());
                broadcastLatencyUpdate(player);
            }
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        uninjectPlayer(event.getPlayer());
        currentLivePings.remove(uuid);
    }

    private boolean shouldSpoof(Player player) {
        if (customBasePings.containsKey(player.getUniqueId())) {
            return true;
        }
        return getConfig().getBoolean("auto-spoof-zero-ping", true) && player.getPing() == 0;
    }

    private void assignNewJitter(UUID uuid) {
        int base = customBasePings.computeIfAbsent(uuid, id -> {
            int min = getConfig().getInt("min-ping", 38);
            int max = getConfig().getInt("max-ping", 135);
            return ThreadLocalRandom.current().nextInt(min, Math.max(min + 1, max + 1));
        });
        int jitter = ThreadLocalRandom.current().nextInt(-10, 13);
        currentLivePings.put(uuid, Math.max(12, base + jitter));
    }

    private void tickPingJitter() {
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (shouldSpoof(target)) {
                assignNewJitter(target.getUniqueId());
                broadcastLatencyUpdate(target);
            }
        }
    }

    private void broadcastLatencyUpdate(Player target) {
        try {
            int spoofedPing = currentLivePings.getOrDefault(target.getUniqueId(), 65);
            Object nmsTarget = craftPlayerGetHandle.invoke(target);
            Object gameProfile = nmsTarget.getClass().getMethod("getGameProfile").invoke(nmsTarget);

            Object entry = buildEntry(
                    target.getUniqueId(),
                    gameProfile,
                    true,
                    spoofedPing,
                    null,
                    null,
                    true,
                    0,
                    null
            );

            @SuppressWarnings({"rawtypes", "unchecked"})
            EnumSet actions = EnumSet.of((Enum) updateLatencyEnumConstant);
            Object packet = packetConstructor.newInstance(actions, List.of(entry));

            for (Player viewer : Bukkit.getOnlinePlayers()) {
                Channel channel = getChannel(viewer);
                if (channel != null && channel.isActive()) {
                    channel.writeAndFlush(packet);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void injectPlayer(Player player) {
        Channel channel = getChannel(player);
        if (channel == null || channel.pipeline().get(HANDLER_NAME) != null) return;

        ChannelDuplexHandler handler = new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (packetClass.isInstance(msg)) {
                    msg = rewritePacketIfNeeded(msg);
                }
                super.write(ctx, msg, promise);
            }
        };

        try {
            if (channel.pipeline().get("packet_handler") != null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
            }
        } catch (Exception ignored) {
        }
    }

    private void uninjectPlayer(Player player) {
        Channel channel = getChannel(player);
        if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME);
        }
    }

    private Object rewritePacketIfNeeded(Object packet) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) entriesField.get(packet);
            if (entries == null || entries.isEmpty()) return packet;

            boolean modified = false;
            List<Object> updatedEntries = new ArrayList<>(entries.size());

            for (Object entry : entries) {
                UUID profileId = (UUID) entryProfileIdField.get(entry);
                int latency = entryLatencyField.getInt(entry);

                Player target = Bukkit.getPlayer(profileId);
                boolean isSpoofed = customBasePings.containsKey(profileId)
                        || (getConfig().getBoolean("auto-spoof-zero-ping", true)
                        && latency == 0
                        && (target == null || target.getPing() == 0));

                if (isSpoofed) {
                    int spoofedPing = currentLivePings.computeIfAbsent(profileId, id -> {
                        assignNewJitter(id);
                        return currentLivePings.get(id);
                    });

                    Object newEntry = cloneEntryWithLatency(entry, spoofedPing);
                    updatedEntries.add(newEntry);
                    modified = true;
                } else {
                    updatedEntries.add(entry);
                }
            }

            if (modified) {
                entriesField.set(packet, Collections.unmodifiableList(updatedEntries));
            }
        } catch (Exception ignored) {
        }
        return packet;
    }

    private Object cloneEntryWithLatency(Object oldEntry, int newLatency) throws Exception {
        UUID id = (UUID) entryProfileIdField.get(oldEntry);
        Object profile = entryProfileField.get(oldEntry);
        boolean listed = entryListedField.getBoolean(oldEntry);
        Object gameMode = entryGameModeField.get(oldEntry);
        Object displayName = entryDisplayNameField.get(oldEntry);
        boolean showHat = entryShowHatField != null && entryShowHatField.getBoolean(oldEntry);
        int listOrder = entryListOrderField != null ? entryListOrderField.getInt(oldEntry) : 0;
        Object chatSession = entryChatSessionField.get(oldEntry);

        return buildEntry(id, profile, listed, newLatency, gameMode, displayName, showHat, listOrder, chatSession);
    }

    private Object buildEntry(UUID id, Object profile, boolean listed, int latency,
                              Object gameMode, Object displayName, boolean showHat,
                              int listOrder, Object chatSession) throws Exception {
        int paramCount = entryConstructor.getParameterCount();
        if (paramCount == 9) {
            return entryConstructor.newInstance(id, profile, listed, latency, gameMode, displayName, showHat, listOrder, chatSession);
        } else if (paramCount == 8) {
            return entryConstructor.newInstance(id, profile, listed, latency, gameMode, displayName, listOrder, chatSession);
        } else {
            return entryConstructor.newInstance(id, profile, listed, latency, gameMode, displayName, chatSession);
        }
    }

    private Channel getChannel(Player player) {
        try {
            Object handle = craftPlayerGetHandle.invoke(player);
            Object connection = connectionField.get(handle);
            if (connection == null) return null;
            Object networkManager = networkManagerField.get(connection);
            return (Channel) channelField.get(networkManager);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean initReflection() {
        try {
            String craftPkg = Bukkit.getServer().getClass().getPackage().getName();
            Class<?> craftPlayerClass = Class.forName(craftPkg + ".entity.CraftPlayer");
            craftPlayerGetHandle = craftPlayerClass.getMethod("getHandle");

            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            for (Field f : serverPlayerClass.getFields()) {
                if (f.getType().getName().endsWith("ServerGamePacketListenerImpl")) {
                    connectionField = f;
                    break;
                }
            }

            Class<?> listenerClass = connectionField.getType();
            Class<?> current = listenerClass;
            while (current != null && networkManagerField == null) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.getType().getName().endsWith("Connection")) {
                        f.setAccessible(true);
                        networkManagerField = f;
                        break;
                    }
                }
                current = current.getSuperclass();
            }

            for (Field f : networkManagerField.getType().getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    channelField = f;
                    break;
                }
            }

            packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            for (Field f : packetClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (List.class.isAssignableFrom(f.getType())) {
                    entriesField = f;
                }
            }

            Class<?> actionEnum = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Enum<?> latencyConst = Enum.valueOf((Class<Enum>) actionEnum, "UPDATE_LATENCY");
            updateLatencyEnumConstant = latencyConst;
            packetConstructor = packetClass.getConstructor(EnumSet.class, Collection.class);

            entryClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
            entryConstructor = entryClass.getDeclaredConstructors()[0];
            entryConstructor.setAccessible(true);

            Field[] fields = entryClass.getDeclaredFields();
            List<Field> instanceFields = new ArrayList<>();
            for (Field f : fields) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    instanceFields.add(f);
                }
            }

            entryProfileIdField = instanceFields.get(0);
            entryProfileField = instanceFields.get(1);
            entryListedField = instanceFields.get(2);
            entryLatencyField = instanceFields.get(3);
            entryGameModeField = instanceFields.get(4);
            entryDisplayNameField = instanceFields.get(5);

            if (instanceFields.size() >= 9) {
                entryShowHatField = instanceFields.get(6);
                entryListOrderField = instanceFields.get(7);
                entryChatSessionField = instanceFields.get(8);
            } else if (instanceFields.size() == 8) {
                entryListOrderField = instanceFields.get(6);
                entryChatSessionField = instanceFields.get(7);
            } else {
                entryChatSessionField = instanceFields.get(6);
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /botping <player|reload> [ping|auto]");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aBotPingSpoof config reloaded.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer/bot not found online.");
            return true;
        }

        if (args.length == 1 || args[1].equalsIgnoreCase("auto")) {
            customBasePings.remove(target.getUniqueId());
            assignNewJitter(target.getUniqueId());
            broadcastLatencyUpdate(target);
            sender.sendMessage("§aSet §f" + target.getName() + " §ato auto fluctuating ping (§e"
                    + currentLivePings.get(target.getUniqueId()) + "ms§a).");
            return true;
        }

        try {
            int ping = Integer.parseInt(args[1]);
            customBasePings.put(target.getUniqueId(), ping);
            assignNewJitter(target.getUniqueId());
            broadcastLatencyUpdate(target);
            sender.sendMessage("§aSet §f" + target.getName() + " §abase ping to §e" + ping + "ms§a.");
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid number. Use /botping <player> <ms|auto>");
        }
        return true;
    }
}
