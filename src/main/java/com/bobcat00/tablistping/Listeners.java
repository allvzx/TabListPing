// TabListPing - Displays ping time in CraftBukkit/Spigot player list
// Copyright 2019 Bobcat00
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bobcat00.tablistping;

import com.comphenix.tinyprotocol.Reflection;
import com.earth2me.essentials.User;
import com.mojang.authlib.GameProfile;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import com.earth2me.essentials.IEssentials;

import net.ess3.api.IUser;
import net.ess3.api.events.AfkStatusChangeEvent;

import java.lang.reflect.Method;
import java.util.*;

public final class Listeners implements Listener {
    private TabListPing plugin;
    private IEssentials ess;

    // Map containing Keep Alive time and ping time
    public Map<UUID, List<Long>> keepAliveTime = Collections.synchronizedMap(new HashMap<UUID, List<Long>>());

    // Constructor

    public Listeners(TabListPing plugin) {
        this.plugin = plugin;

        // Hook in to Essentials
        Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials != null && essentials.isEnabled()) {
            ess = (IEssentials) essentials;
        }

        // Register events

        plugin.getServer().getPluginManager().registerEvent(PlayerQuitEvent.class, this, EventPriority.MONITOR,
                new EventExecutor() {
                    public void execute(Listener l, Event e) {
                        onPlayerQuit((PlayerQuitEvent) e);
                    }
                },
                plugin, true); // ignoreCancelled=true

        if (ess != null) {
            plugin.getServer().getPluginManager().registerEvent(AfkStatusChangeEvent.class, this, EventPriority.MONITOR,
                    new EventExecutor() {
                        public void execute(Listener l, Event e) {
                            onAfk((AfkStatusChangeEvent) e);
                        }
                    },
                    plugin, true); // ignoreCancelled=true);
        }
    }

    // -------------------------------------------------------------------------

    // Keep Alive from server to client

    public void processServerToClient(Player player) {
        // Save time in hashmap
        UUID uuid = player.getUniqueId();
        Long currentTime = System.currentTimeMillis();
        List<Long> timeData = keepAliveTime.get(uuid); // possibly blocking
        if (timeData == null) {
            timeData = new ArrayList<Long>(2);
            timeData.add(0L);
            timeData.add(0L);
        }
        timeData.set(0, currentTime);
        keepAliveTime.put(uuid, timeData); // possibly blocking
    }

    // -------------------------------------------------------------------------

    // Keep Alive response from client to server

    public void processClientToServer(Player player) {
        // Get time from hashmap and calculate ping time in msec
        Long currentTime = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        Long pingTime = 0L;
        List<Long> timeData = keepAliveTime.get(uuid); // possibly blocking
        if (timeData == null) {
            timeData = new ArrayList<Long>(2);
            timeData.add(0L);
            timeData.add(0L);
        } else {
            pingTime = currentTime - timeData.get(0);
            timeData.set(1, pingTime);
        }
        keepAliveTime.put(uuid, timeData); // possibly blocking
    }

    // nms classes
    private final Class<Enum> playerInfoActionEnumClass = (Class<Enum>) Reflection.getClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$a");
    private final Class<Enum> gamemodeEnumClass = (Class<Enum>) Reflection.getClass("net.minecraft.world.level.EnumGamemode");
    private final Class<Object> chatBaseComponentClass = Reflection.getUntypedClass("net.minecraft.network.chat.IChatBaseComponent");
    private final Class<Object> remoteChatSessionDataClass = Reflection.getUntypedClass("net.minecraft.network.chat.RemoteChatSession$a");
    private final Class<Object> playerInfoClass = Reflection.getUntypedClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$b");
    private final Reflection.ConstructorInvoker playerInfoConstructor = Reflection.getConstructor(
            "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$b",
            UUID.class,
            GameProfile.class,
            boolean.class,
            int.class,
            gamemodeEnumClass,
            chatBaseComponentClass,
            remoteChatSessionDataClass);

    // fields
    private final Reflection.FieldAccessor<EnumSet> playerInfoActionsField = Reflection.getField(plugin.OUT_PLAYER_INFO_UPDATE_PACKET, EnumSet.class, 0);
    private final Reflection.FieldAccessor<List> playerInfoListField = Reflection.getField(plugin.OUT_PLAYER_INFO_UPDATE_PACKET, List.class, 0);
    private final Reflection.FieldAccessor<UUID> playerInfoUUIDField = Reflection.getField(playerInfoClass, UUID.class, 0);
    private final Reflection.FieldAccessor<GameProfile> playerInfoGameProfileField = Reflection.getField(playerInfoClass, GameProfile.class, 0);
    private final Reflection.FieldAccessor<Object> playerInfoDisplayNameField = Reflection.getField(playerInfoClass, chatBaseComponentClass, 0);

    // methods
    private final Reflection.MethodInvoker componentEmptyMethod = Reflection.getMethod(chatBaseComponentClass, "i");
    private final Reflection.MethodInvoker mutableComponentAppendStringMethod = Reflection.getMethod("net.minecraft.network.chat.IChatMutableComponent", "f", String.class);
    private final Reflection.MethodInvoker mutableComponentAppendComponentMethod = Reflection.getMethod("net.minecraft.network.chat.IChatMutableComponent", "b", chatBaseComponentClass);

    public Object onPlayerInfoUpdate(Object packet) throws Exception {
        EnumSet<?> actions = playerInfoActionsField.get(packet);
        EnumSet<?> newActions = EnumSet.copyOf(actions);

        if (actions.contains(Enum.valueOf(playerInfoActionEnumClass, "UPDATE_LATENCY")) && !actions.contains(Enum.valueOf(playerInfoActionEnumClass, "UPDATE_DISPLAY_NAME"))) {
            Method enumAdd = newActions.getClass().getMethod("add", Enum.class);
            enumAdd.setAccessible(true);
            enumAdd.invoke(newActions, Enum.valueOf(playerInfoActionEnumClass, "UPDATE_DISPLAY_NAME"));
            playerInfoActionsField.set(packet, newActions);
        }

        if (newActions.contains(Enum.valueOf(playerInfoActionEnumClass, "UPDATE_DISPLAY_NAME"))) {
            List<?> playersInfo = playerInfoListField.get(packet);
            ArrayList<Object> newPlayersInfo = new ArrayList<>();
            for (Object playerInfo : playersInfo) {
                UUID uuid = playerInfoUUIDField.get(playerInfo);
                GameProfile profile = playerInfoGameProfileField.get(playerInfo);
                Object displayName = playerInfoDisplayNameField.get(playerInfo);

                if (displayName == null) {
                    displayName = componentEmptyMethod.invoke(null);
                    mutableComponentAppendStringMethod.invoke(displayName, profile.getName());
                }

                List<Long> timeData = keepAliveTime.get(uuid);
                Long ping = 0L;
                if (timeData != null) {
                    ping = timeData.get(1);
                }

                boolean afk = false;
                if (ess != null) {
                    User user = ess.getUser(uuid);
                    afk = user != null && user.isAfk();
                }

                String pingPrefix = ChatColor.translateAlternateColorCodes('&',
                        "&7[&a%ping%ms&7] "
                                .replace("%ping%", ping.toString()));
                String afkSuffix = ChatColor.translateAlternateColorCodes('&',
                        " &eAFK");

                Object components = componentEmptyMethod.invoke(null);
                mutableComponentAppendStringMethod.invoke(components, pingPrefix);
                mutableComponentAppendComponentMethod.invoke(components, displayName);
                if (afk)
                    mutableComponentAppendStringMethod.invoke(components, afkSuffix);

                Object newPlayerInfo = playerInfoConstructor.invoke(
                        uuid, // uuid
                        profile, // game profile
                        Reflection.getField(playerInfoClass, boolean.class, 0).get(playerInfo), // listed
                        Reflection.getField(playerInfoClass, int.class, 0).get(playerInfo), // latency
                        Reflection.getField(playerInfoClass, gamemodeEnumClass, 0).get(playerInfo), // game mode
                        components, // display name
                        Reflection.getField(playerInfoClass, remoteChatSessionDataClass, 0).get(playerInfo) // chat session
                );

                newPlayersInfo.add(newPlayerInfo);
            }
            playerInfoListField.set(packet, newPlayersInfo.stream().toList());
        }
        return packet;
    }

    // AFK change

    public void onAfk(AfkStatusChangeEvent event) {
        IUser user = event.getAffected();
        Player player = user.getBase();
        if (player.isOnline()) {
            player.setPlayerListName(player.getDisplayName()); // trigger display name update
        }
    }

    // Player quit or was kicked

    public void onPlayerQuit(PlayerQuitEvent event) {
        // Remove player's entry from hashmap
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        keepAliveTime.remove(uuid); // possibly blocking
    }
}    
