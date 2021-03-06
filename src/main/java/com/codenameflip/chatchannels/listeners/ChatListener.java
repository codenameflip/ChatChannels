package com.codenameflip.chatchannels.listeners;

import com.codenameflip.chatchannels.events.ChannelChatEvent;
import com.codenameflip.chatchannels.events.ChannelPreChatEvent;
import com.codenameflip.chatchannels.structure.Channel;
import com.codenameflip.chatchannels.utils.cooldowns.Cooldowns;
import com.codenameflip.chatchannels.utils.cooldowns.CooldownsManager;
import com.codenameflip.chatchannels.utils.Language;
import com.codenameflip.chatchannels.utils.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * ChatChannels
 *
 * @author Cameron Fletcher
 * @since 02/07/2018
 */
public class ChatListener implements ChatChannelsListener {

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (message.startsWith("/")) return;

        if (getRegistry() == null) return; // If chat channels couldn't successfully make a registry instance then handle chat vanilla.
        else event.setCancelled(true);

        Optional<Channel> c = getRegistry().getFocusedChannel(player.getUniqueId());

        if (!c.isPresent()) {
            getRegistry().getAutoShowChannels().forEach(all -> getRegistry().showChannel(player, all));
            getRegistry().getAutoFocusChannels().forEach(all -> getRegistry().focusChannel(player, all));
        }

        c = getRegistry().getFocusedChannel(player.getUniqueId()); // Update the channel, just in case it was null before and is now set.
        Channel channel = c.get(); // Unbox

        // Handle mute management
        if (channel.getProperties().isMuted()) {
            if (!player.hasPermission("chatchannels.bypass-mute")) {
                Language.localeChat(player, "CANNOT_CHAT", null);
                return;
            }
        }

        // Handle permission management
        if (!channel.getProperties().getPermission().equalsIgnoreCase("*")
                && !player.hasPermission(channel.getProperties().getPermission())) {
            Language.localeChat(player, "NO_PERMS", null);

            if (get().getConfig().getBoolean("chat-settings.announce-permissions"))
                Language.localeChat(player, "NO_PERMS_EXACT", new Placeholders("permission", channel.getProperties().getPermission()).build());

            return;
        }

        // Confirm they're viewing the channel being chatted in
        if (!channel.getViewingPlayers().contains(player.getUniqueId())) {
            Language.localeChat(player, "NOT_VIEWING", null);
            getRegistry().showChannel(player, channel);
        }

        ChannelPreChatEvent preChatEvent = new ChannelPreChatEvent(player, channel, message);
        Bukkit.getPluginManager().callEvent(preChatEvent);

        // Make sure the event isn't being hijacked and cancelled elsewhere
        if (!preChatEvent.isCancelled()) {
            String formattedMessage = getRegistry().formatMessage(player, message, channel);
            String cooldownId = "COOLDOWN_" + channel.getDisplayName();

            if (!Cooldowns.isDone(player, cooldownId)) {
                Language.localeChat(player, "ON_COOLDOWN", new Placeholders("remaining", CooldownsManager.getTimeRemaining(player.getUniqueId(), cooldownId)).build());
                return;
            }

            ChannelChatEvent chatEvent = new ChannelChatEvent(player, channel, message);
            Bukkit.getPluginManager().callEvent(chatEvent);

            // Radius management
            if (channel.getProperties().getChatRadius() > 0) {
                String warningId = "COOLDOWN_WARN_RADIUS_" + channel.getDisplayName();
                double radius = channel.getProperties().getChatRadius();

                player.sendMessage(formattedMessage); // Make sure the player themselves can see the message they sent.

                player.getNearbyEntities(radius, radius, radius).stream()
                        .filter(ent -> ent.getType() == EntityType.PLAYER)
                        .filter(p -> channel.getViewingPlayers().contains(p.getUniqueId()))
                        .forEach(p -> p.sendMessage(formattedMessage));

                // Radius warning
                if (Cooldowns.isDone(player, warningId)) {
                    Cooldowns.on(player)
                            .forTime(TimeUnit.MINUTES.toMillis(5))
                            .forReason(warningId)
                            .done();

                    Language.localeChat(player, "WARN_RADIUS", new Placeholders().put("channel", channel.getDisplayName()).put("radius", radius).build());
                }
            } else channel.broadcastRaw(formattedMessage);

            // Chat cooldown management
            if (channel.getProperties().getCooldown() > 0 && !player.hasPermission("chatchannels.bypass-cooldown")) {
                Cooldowns.on(player)
                        .forTime(TimeUnit.SECONDS.toMillis((long) channel.getProperties().getCooldown()))
                        .forReason(cooldownId)
                        .done();
            }
        }
    }

}
