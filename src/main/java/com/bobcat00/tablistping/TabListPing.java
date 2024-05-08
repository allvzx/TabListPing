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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import com.earth2me.essentials.User;
import com.mojang.authlib.GameProfile;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import com.comphenix.tinyprotocol.Reflection;
import com.comphenix.tinyprotocol.TinyProtocol;

import io.netty.channel.Channel;

public class TabListPing extends JavaPlugin implements Listener {
    Listeners listeners;
    TinyProtocol protocol;

    private Class<?> OUT_KEEP_ALIVE_PACKET;
    private Class<?> IN_KEEP_ALIVE_PACKET;
    private final Class<?> OUT_PLAYER_INFO_UPDATE_PACKET = Reflection.getClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");

    @Override
    public void onEnable() {
        /*this.saveDefaultConfig();

        this.getConfig().options().setHeader(Arrays.asList("Supported variables are %name%, %displayname%, and %ping%"));
        this.getConfig().setComments("format", null); // get rid of old comments added improperly

        if (!this.getConfig().contains("format-afk", true)) {
            this.getConfig().set("format-afk", this.getConfig().getString("format") + " &eAFK");
        }
        this.saveConfig();*/

        listeners = new Listeners(this);

        // Protocol hooks

        String bukkitVersion = getServer().getBukkitVersion().split("-")[0];
        String outClassName;
        String inClassName;

        // The idea here is to define the class names for old Minecraft versions,
        // handling current and future versions in the default case.
        // Use Spigot mappings.
        switch (bukkitVersion) {
            case "1.18":
            case "1.18.1":
            case "1.18.2":
            case "1.19":
            case "1.19.1":
            case "1.19.2":
            case "1.19.3":
            case "1.19.4":
            case "1.20":
            case "1.20.1":
                outClassName = "net.minecraft.network.protocol.game.PacketPlayOutKeepAlive";
                inClassName = "net.minecraft.network.protocol.game.PacketPlayInKeepAlive";
                break;
            default:
                outClassName = "net.minecraft.network.protocol.common.ClientboundKeepAlivePacket";
                inClassName = "net.minecraft.network.protocol.common.ServerboundKeepAlivePacket";
                break;
        }
        getLogger().info("Detected " + bukkitVersion + ", using " + outClassName + " & " + inClassName);
        OUT_KEEP_ALIVE_PACKET = Reflection.getClass(outClassName);
        IN_KEEP_ALIVE_PACKET = Reflection.getClass(inClassName);

        this.protocol = new TinyProtocol(this) {
            // nms classes
            private Class<Object> chatBaseComponentClass = Reflection.getUntypedClass("net.minecraft.network.chat.IChatBaseComponent");
            private Class<Enum> gamemodeEnumClass = (Class<Enum>) Reflection.getClass("net.minecraft.world.level.EnumGamemode");
            private Class<Object> remoteChatSessionDataClass = Reflection.getUntypedClass("net.minecraft.network.chat.RemoteChatSession$a");
            Class<Enum> playerInfoActionEnumClass = (Class<Enum>) Reflection.getClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$a");
            Class<Object> playerInfoClass = Reflection.getUntypedClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$b");
            Reflection.ConstructorInvoker playerInfoConstructor = Reflection.getConstructor(
                    "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$b",
                    UUID.class,
                    GameProfile.class,
                    boolean.class,
                    int.class,
                    gamemodeEnumClass,
                    chatBaseComponentClass,
                    remoteChatSessionDataClass);

            // fields
            Reflection.FieldAccessor<EnumSet> playerInfoActionsField = Reflection.getField(OUT_PLAYER_INFO_UPDATE_PACKET, EnumSet.class, 0);
            Reflection.FieldAccessor<List> playerInfoListField = Reflection.getField(OUT_PLAYER_INFO_UPDATE_PACKET, List.class, 0);
            Reflection.FieldAccessor<UUID> playerInfoUUIDField = Reflection.getField(playerInfoClass, UUID.class, 0);
            Reflection.FieldAccessor<GameProfile> playerInfoGameProfileField = Reflection.getField(playerInfoClass, GameProfile.class, 0);
            Reflection.FieldAccessor<Object> playerInfoDisplayNameField = Reflection.getField(playerInfoClass, chatBaseComponentClass, 0);

            // methods
            Reflection.MethodInvoker componentEmptyMethod = Reflection.getMethod(chatBaseComponentClass, "i");
            Reflection.MethodInvoker mutableComponentAppendStringMethod = Reflection.getMethod("net.minecraft.network.chat.IChatMutableComponent", "f", String.class);
            Reflection.MethodInvoker mutableComponentAppendComponentMethod = Reflection.getMethod("net.minecraft.network.chat.IChatMutableComponent", "b", chatBaseComponentClass);

            // Server to client
            @Override
            public Object onPacketOutAsync(Player receiver, Channel channel, Object packet) throws Exception {
                if (OUT_KEEP_ALIVE_PACKET.isInstance(packet)) {
                    listeners.processServerToClient(receiver);
                } else if (OUT_PLAYER_INFO_UPDATE_PACKET.isInstance(packet)) {
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

                            List<Long> timeData = listeners.keepAliveTime.get(uuid);
                            Long ping = 0L;
                            if (timeData != null) {
                                ping = timeData.get(1);
                            }

                            boolean afk = false;
                            if (listeners.ess != null) {
                                User user = listeners.ess.getUser(uuid);
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
                                    Reflection.getField(playerInfoClass, gamemodeEnumClass, 0).get(playerInfo), //
                                    components, // display name
                                    Reflection.getField(playerInfoClass, remoteChatSessionDataClass, 0).get(playerInfo) // chat session
                            );

                            newPlayersInfo.add(newPlayerInfo);
                        }
                        playerInfoListField.set(packet, newPlayersInfo.stream().toList());
                    }
                    return packet;
                }
                return super.onPacketOutAsync(receiver, channel, packet);
            }

            // Client to server
            @Override
            public Object onPacketInAsync(Player sender, Channel channel, Object packet) {
                if (IN_KEEP_ALIVE_PACKET.isInstance(packet)) {
                    listeners.processClientToServer(sender);
                }
                return super.onPacketInAsync(sender, channel, packet);
            }
        };
    }

    @Override
    public void onDisable() {
        //
    }
}
