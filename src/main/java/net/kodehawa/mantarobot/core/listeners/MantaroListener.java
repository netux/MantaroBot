/*
 * Copyright (C) 2016-2020 David Rubio Escares / Kodehawa
 *
 *  Mantaro is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  Mantaro is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro.  If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.core.listeners;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.*;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.http.HttpRequestEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.hooks.EventListener;
import net.kodehawa.mantarobot.ExtraRuntimeOptions;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.BirthdayCmd;
import net.kodehawa.mantarobot.commands.currency.TextChannelGround;
import net.kodehawa.mantarobot.commands.custom.EmbedJSON;
import net.kodehawa.mantarobot.commands.custom.legacy.DynamicModifiers;
import net.kodehawa.mantarobot.core.MantaroCore;
import net.kodehawa.mantarobot.core.MantaroEventManager;
import net.kodehawa.mantarobot.core.listeners.entities.CachedMessage;
import net.kodehawa.mantarobot.data.Config;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.ManagedDatabase;
import net.kodehawa.mantarobot.db.entities.PremiumKey;
import net.kodehawa.mantarobot.log.LogUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.data.JsonDataManager;
import net.kodehawa.mantarobot.utils.exporters.Metrics;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@SuppressWarnings("CatchMayIgnoreException")
public class MantaroListener implements EventListener {
    private static final Logger log = LoggerFactory.getLogger(MantaroListener.class);

    private static int logTotal = 0;
    private final ManagedDatabase db = MantaroData.db();
    private final DateFormat df = new SimpleDateFormat("HH:mm:ss");
    private final SecureRandom rand = new SecureRandom();
    private final ExecutorService threadPool;
    private final Cache<Long, Optional<CachedMessage>> messageCache;

    private final Pattern modifierPattern = Pattern.compile("\\b\\p{L}*:\\b");

    //Channels we could send the greet message to.
    private final List<String> channelNames =
            List.of("general", "general-chat", "chat", "lounge", "main-chat", "main");
    private final Config config = MantaroData.config().get();

    public MantaroListener(ExecutorService threadPool,
                           Cache<Long, Optional<CachedMessage>> messageCache) {
        this.threadPool = threadPool;
        this.messageCache = messageCache;
    }

    public static int getLogTotal() {
        return logTotal;
    }

    @Override
    public void onEvent(@NotNull GenericEvent event) {
        if (event instanceof ReadyEvent) {
            this.updateStats(event.getJDA());
            return;
        }

        if (event instanceof GuildMessageReceivedEvent) {
            Metrics.RECEIVED_MESSAGES.inc();
            return;
        }

        if (event instanceof GuildMemberJoinEvent) {
            threadPool.execute(() -> onUserJoin((GuildMemberJoinEvent) event));
            return;
        }

        if (event instanceof GuildMemberRemoveEvent) {
            threadPool.execute(() -> onUserLeave((GuildMemberRemoveEvent) event));
            return;
        }

        //Log intensifies
        //Doesn't run on the thread pool as there's no need for it.
        if (event instanceof GuildMemberRoleAddEvent) {
            //It only runs on the thread pool if needed.
            handleNewPatron((GuildMemberRoleAddEvent) event);
            return;
        }

        if (event instanceof GuildMessageUpdateEvent) {
            logEdit((GuildMessageUpdateEvent) event);
            return;
        }

        if (event instanceof GuildMessageDeleteEvent) {
            logDelete((GuildMessageDeleteEvent) event);
            return;
        }

        MantaroBot instance = MantaroBot.getInstance();

        //Internal events
        if (event instanceof GuildJoinEvent) {
            var joinEvent = (GuildJoinEvent) event;
            if (joinEvent.getGuild().getSelfMember().getTimeJoined().isBefore(OffsetDateTime.now().minusSeconds(30)))
                return;

            onJoin(joinEvent);

            if (MantaroCore.hasLoadedCompletely()) {
                Metrics.GUILD_COUNT.set(instance.getShardManager().getGuildCache().size());
                Metrics.USER_COUNT.set(instance.getShardManager().getUserCache().size());
            }

            return;
        }

        if (event instanceof GuildLeaveEvent) {
            GuildLeaveEvent guildLeaveEvent = (GuildLeaveEvent) event;
            onLeave(guildLeaveEvent);

            var guild = (guildLeaveEvent).getGuild();
            //Destroy this link. Avoid creating a new one by checking if we actually do have an audio manager here.
            var manager = instance.getAudioManager()
                    .getMusicManagers()
                    .get(guild.getId());

            if (manager != null) {
                manager.getLavaLink().resetPlayer();
                manager.getLavaLink().destroy();
                instance.getAudioManager().getMusicManagers().remove(guild.getId());
            }


            if (MantaroCore.hasLoadedCompletely()) {
                Metrics.GUILD_COUNT.set(instance.getShardManager().getGuildCache().size());
                Metrics.USER_COUNT.set(instance.getShardManager().getUserCache().size());
            }

            return;
        }

        //debug
        if (event instanceof StatusChangeEvent) {
            logStatusChange((StatusChangeEvent) event);
            return;
        }

        if (event instanceof DisconnectEvent) {
            Metrics.SHARD_EVENTS.labels("disconnect").inc();
            onDisconnect((DisconnectEvent) event);
            return;
        }

        if (event instanceof ResumedEvent) {
            Metrics.SHARD_EVENTS.labels("resume").inc();
            return;
        }

        if (event instanceof ExceptionEvent) {
            onException((ExceptionEvent) event);
            return;
        }

        if (event instanceof HttpRequestEvent) {
            // We've fucked up big time if we reach this
            if (((HttpRequestEvent) event).isRateLimit()) {
                Metrics.HTTP_429_REQUESTS.inc();
            }

            Metrics.HTTP_REQUESTS.inc();
        }
    }

    /**
     * Handles automatic deliver of patreon keys. Should only deliver keys when
     * - An user was already in the guild or just joined and got the "Patreon" role assigned by the Patreon bot
     * - The user hasn't re-joined to get the role re-assigned
     * - The user hasn't received any keys
     * - The user pledged, obviously
     *
     * @param event The event that says that a role got added, obv.
     */
    private void handleNewPatron(GuildMemberRoleAddEvent event) {
        //Only in mantaro's guild...
        if (event.getGuild().getIdLong() == 213468583252983809L && !MantaroData.config().get().isPremiumBot()) {
            threadPool.execute(() -> {
                var user = event.getUser();
                var dbUser = db.getUser(user);
                var currentKey = MantaroData.db().getPremiumKey(dbUser.getData().getPremiumKey());

                if (event.getMember().getRoles().stream().anyMatch(r -> r.getId().equals("290257037072531466"))) {
                    if (!dbUser.getData().hasReceivedFirstKey() && (currentKey == null || currentKey.validFor() < 20)) {
                        //Attempt to open a PM and send a key!
                        user.openPrivateChannel().queue(channel -> {
                            //Sellout message :^)
                            channel.sendMessage(EmoteReference.EYES +
                                    "Thanks you for donating, we'll deliver your premium key shortly! :heart:")
                                    .queue(message -> {
                                        message.editMessage(EmoteReference.POPPER +
                                                "You received a premium key due to your donation to mantaro. " +
                                                "If any doubts, please contact Kodehawa#3457.\n" +
                                                "Instructions: **Apply this key to yourself!**. " +
                                                "This key is a subscription to Mantaro Premium. " +
                                                "This will last as long as you pledge. If you want more keys (>$2 donation) " +
                                                "or want to enable the patreon bot (>$4 donation)" +
                                                " you need to contact Kodehawa to deliver your keys.\n" +
                                                "To apply this key, run the following command in any channel `~>activatekey " +
                                                PremiumKey.generatePremiumKey(user.getId(), PremiumKey.Type.USER, false).getId()
                                                + "`\nThanks you soo much for donating and helping to keep Mantaro alive! :heart:"
                                        ).queue(sent -> {
                                            dbUser.getData().setHasReceivedFirstKey(true);
                                            dbUser.saveUpdating();
                                        }
                                );

                                Metrics.PATRON_COUNTER.inc();
                                //Celebrate internally! \ o /
                                LogUtils.log(
                                        "Delivered premium key to " + user.getAsTag() + "(" + user.getId() + ")"
                                );
                            });
                        }, failure -> LogUtils.log(
                                String.format("User: %s (%s) couldn't receive the key, apply manually when asked!", user.getId(), user.getAsTag()))
                        );
                    }
                }
            });
        }
    }

    private void logDelete(GuildMessageDeleteEvent event) {
        try {
            final var db = MantaroData.db();
            final var dbGuild = db.getGuild(event.getGuild());
            final var data = dbGuild.getData();
            final var logChannel = data.getGuildLogChannel();

            final var hour = Utils.formatHours(OffsetDateTime.now(), data.getLang());
            if (logChannel != null) {
                var tc = event.getGuild().getTextChannelById(logChannel);
                if (tc == null) {
                    return;
                }

                var deletedMessage =
                        messageCache.get(event.getMessageIdLong(), Optional::empty).orElse(null);

                final var author = deletedMessage.getAuthor();

                if (deletedMessage != null &&
                        !deletedMessage.getContent().isEmpty() && !event.getChannel().getId().equals(logChannel)
                        && !author.getId().equals(event.getJDA().getSelfUser().getId())) {
                    if (data.getModlogBlacklistedPeople().contains(author.getId())) {
                        return;
                    }

                    if (data.getLogExcludedChannels().contains(event.getChannel().getId())) {
                        return;
                    }

                    if (!data.getModLogBlacklistWords().isEmpty()) {
                        //This is not efficient at all I'm pretty sure, is there a better way?
                        List<String> splitMessage = Arrays.asList(deletedMessage.getContent().split("\\s+"));
                        if (data.getModLogBlacklistWords().stream().anyMatch(splitMessage::contains)) {
                            return;
                        }
                    }

                    String message;
                    if (data.getDeleteMessageLog() != null) {
                        message = new DynamicModifiers()
                                .set("hour", hour)
                                .set("content", deletedMessage.getContent().replace("```", ""))
                                .mapEvent("event", event)
                                .mapChannel("event.channel", event.getChannel())
                                .mapUser("event.user", author)
                                .set("event.message.id", event.getMessageId())
                                .resolve(data.getDeleteMessageLog());
                    } else {
                        message = String.format(EmoteReference.WARNING +
                                        "`[%s]` Message (ID: %s) created by **%s#%s** (ID: %s) in channel **%s** was deleted.\n" +
                                        "```diff\n-%s```", hour, event.getMessageId(), author.getName(),
                                author.getDiscriminator(),
                                author.getId(), event.getChannel().getName(),
                                deletedMessage.getContent().replace("```", "")
                        );
                    }

                    logTotal++;
                    tc.sendMessage(message).queue();
                }
            }
        } catch (Exception e) {
            if (!(e instanceof IllegalArgumentException) && !(e instanceof NullPointerException)
                    && !(e instanceof CacheLoader.InvalidCacheLoadException) && !(e instanceof PermissionException) &&
                    !(e instanceof ErrorResponseException)) {
                log.warn("Unexpected exception while logging a deleted message.", e);
            }
        }
    }

    private void logEdit(GuildMessageUpdateEvent event) {
        try {
            final var db = MantaroData.db();
            final var guildData = db.getGuild(event.getGuild()).getData();

            var logChannel = guildData.getGuildLogChannel();
            final var hour = Utils.formatHours(OffsetDateTime.now(), guildData.getLang());

            if (logChannel != null) {
                var tc = event.getGuild().getTextChannelById(logChannel);
                if (tc == null) {
                    return;
                }

                var author = event.getAuthor();
                var editedMessage = messageCache.get(event.getMessage().getIdLong(), Optional::empty).orElse(null);
                var content = editedMessage.getContent();

                if (editedMessage != null && !content.isEmpty() && !event.getChannel().getId().equals(logChannel)) {
                    //Update message in cache in any case.
                    Message originalMessage = event.getMessage();
                    messageCache.put(originalMessage.getIdLong(), Optional.of(
                            new CachedMessage(event.getGuild().getIdLong(),
                                    event.getAuthor().getIdLong(), originalMessage.getContentDisplay())
                            )
                    );

                    if (guildData.getLogExcludedChannels().contains(event.getChannel().getId())) {
                        return;
                    }

                    if (guildData.getModlogBlacklistedPeople().contains(editedMessage.getAuthor().getId())) {
                        return;
                    }

                    //Don't log if content is equal but update in cache (cc: message is still relevant).
                    if (originalMessage.getContentDisplay().equals(content))
                        return;

                    if (!guildData.getModLogBlacklistWords().isEmpty()) {
                        //This is not efficient at all I'm pretty sure, is there a better way?
                        List<String> splitMessage = Arrays.asList(content.split("\\s+"));
                        if (guildData.getModLogBlacklistWords().stream().anyMatch(splitMessage::contains)) {
                            return;
                        }
                    }

                    String message;
                    if (guildData.getEditMessageLog() != null) {
                        message = new DynamicModifiers()
                                .set("hour", hour)
                                .set("old", content.replace("```", ""))
                                .set("new", originalMessage.getContentDisplay().replace("```", ""))
                                .mapEvent("event", event)
                                .mapChannel("event.channel", event.getChannel())
                                .mapUser("event.user", editedMessage.getAuthor())
                                .mapMessage("event.message", originalMessage)
                                .resolve(guildData.getEditMessageLog());
                    } else {
                        message = String.format(EmoteReference.WARNING +
                                        "`[%s]` Message (ID: %s) created by **%s#%s** in channel **%s** was modified." +
                                        "\n```diff\n-%s\n+%s```",
                                hour, originalMessage.getId(), author.getName(), author.getDiscriminator(),
                                event.getChannel().getName(), content.replace("```", ""),
                                originalMessage.getContentDisplay().replace("```", "")
                        );
                    }

                    tc.sendMessage(message).queue();

                    logTotal++;
                }
            }
        } catch (Exception e) {
            if (!(e instanceof NullPointerException) && !(e instanceof IllegalArgumentException) &&
                    !(e instanceof CacheLoader.InvalidCacheLoadException) && !(e instanceof PermissionException) &&
                    !(e instanceof ErrorResponseException)) { // Also ignore unknown users.
                log.warn("Unexpected error while logging a edit.", e);
            }
        }
    }

    private void logStatusChange(StatusChangeEvent event) {
        var shardId = event.getJDA().getShardInfo().getShardId();

        if (ExtraRuntimeOptions.VERBOSE_SHARD_LOGS || ExtraRuntimeOptions.VERBOSE) {
            log.info("Shard #{}: Changed from {} to {}", shardId, event.getOldStatus(), event.getNewStatus());
        } else {
            //Very janky solution lol.
            if (event.getNewStatus().ordinal() > JDA.Status.LOADING_SUBSYSTEMS.ordinal())
                log.info("Shard #{}: {}", shardId, event.getNewStatus());

            //Log it to debug eitherway.
            log.debug("Shard #{}: Changed from {} to {}", shardId, event.getOldStatus(), event.getNewStatus());
        }

        this.updateStats(event.getJDA());
    }

    private void onDisconnect(DisconnectEvent event) {
        if (event.isClosedByServer()) {
            log.warn(String.format("---- DISCONNECT [SERVER] CODE: [%,d] %s%n",
                    event.getServiceCloseFrame().getCloseCode(), event.getCloseCode())
            );
        } else {
            log.warn(String.format("---- DISCONNECT [CLIENT] CODE: [%,d] %s%n",
                    event.getClientCloseFrame().getCloseCode(), event.getClientCloseFrame().getCloseReason())
            );
        }
    }

    private void onException(ExceptionEvent event) {
        if (!event.isLogged()) {
            log.error("Exception captured in un-logged trace ({})", event.getCause().getMessage());
        }
    }

    private void onJoin(GuildJoinEvent event) {
        final var guild = event.getGuild();
        final var jda = event.getJDA();
        final var mantaroData = MantaroData.db().getMantaroData();

        try {
            if (mantaroData.getBlackListedGuilds().contains(guild.getId()) ||
                    mantaroData.getBlackListedUsers().contains(guild.getOwner().getUser().getId())) {
                guild.leave().queue();
                return;
            }

            //Don't send greet message for MP. Not necessary.
            if (!config.isPremiumBot()) {
                //Greet message start.
                var embedBuilder = new EmbedBuilder()
                        .setThumbnail(jda.getSelfUser().getEffectiveAvatarUrl())
                        .setColor(Color.PINK)
                        .setDescription("""
                                Welcome to **Mantaro**, a fun, quirky and complete Discord bot! Thanks for adding me to your server, I highly appreciate it <3
                                We have music, currency (money/economy), games and way more stuff you can check out!
                                Make sure you use the `~>help` command to make yourself comfy and to get started with the bot!

                                If you're interested in supporting Mantaro, check out our Patreon page below, it'll greatly help to improve the bot. This message will only be shown once.""")
                        .addField("Important Links",
                        """
                                [Support Server](https://support.mantaro.site) - The place to check if you're lost or if there's an issue with the bot.
                                [Official Wiki](https://github.com/Mantaro/MantaroBot/wiki/) - Good place to check if you're lost.
                                [Custom Commands](https://github.com/Mantaro/MantaroBot/wiki/Custom-Command-%22v3%22) - Great customizability for your server needs!
                                [Configuration](https://github.com/Mantaro/MantaroBot/wiki/Configuration) -  Customizability for your server needs!
                                [Patreon](https://patreon.com/mantaro) - Help Mantaro's development directly by donating a small amount of money each month.
                                [Official Website](https://mantaro.site) - A cool website.""",
                                true
                        ).setFooter("We hope you enjoy using Mantaro! This will self-destruct in 2 minutes.");

                final var dbGuild = db.getGuild(guild);
                final var guildData = dbGuild.getData();

                guild.getChannels().stream().filter(channel -> channel.getType() == ChannelType.TEXT &&
                        channelNames.contains(channel.getName())).findFirst().ifPresentOrElse(ch -> {
                    var channel = (TextChannel) ch;
                    if (channel.canTalk() && !guildData.hasReceivedGreet()) {
                        channel.sendMessage(embedBuilder.build()).queue(m -> m.delete().queueAfter(2, TimeUnit.MINUTES));

                        guildData.setHasReceivedGreet(true);
                        dbGuild.save();
                    } // else ignore
                }, () -> {
                    //Attempt to find the first channel we can talk to.
                    var channel = (TextChannel) guild.getChannels().stream()
                            .filter(guildChannel -> guildChannel.getType() == ChannelType.TEXT && ((TextChannel) guildChannel).canTalk())
                            .findFirst()
                            .orElse(null);

                    //Basically same code as above, but w/e.
                    if (channel != null && !guildData.hasReceivedGreet()) {
                        channel.sendMessage(embedBuilder.build()).queue(m -> m.delete().queueAfter(2, TimeUnit.MINUTES));

                        guildData.setHasReceivedGreet(true);
                        dbGuild.save();
                    }
                });
            }

            //Post bot statistics to the main API.
            this.updateStats(jda);

            Metrics.GUILD_ACTIONS.labels("join").inc();
        } catch (Exception e) {
            if (!(e instanceof NullPointerException) && !(e instanceof IllegalArgumentException)) {
                log.error("Unexpected error while logging an event", e);
            }
        }
    }

    private void onLeave(GuildLeaveEvent event) {
        try {
            final var jda = event.getJDA();
            final var mantaroData = MantaroData.db().getMantaroData();
            final var guild = event.getGuild();
            final var guildBirthdayCache = BirthdayCmd.getGuildBirthdayCache();

            // Clear guild's TextChannel ground.
            guild.getTextChannelCache().stream().forEach(TextChannelGround::delete);
            // Clear per-guild birthday cache
            guildBirthdayCache.invalidate(guild.getId());
            guildBirthdayCache.cleanUp();

            if (mantaroData.getBlackListedGuilds().contains(event.getGuild().getId()) ||
                    mantaroData.getBlackListedUsers().contains(event.getGuild().getOwner().getUser().getId())) {
                log.info("Left {} because of a blacklist entry. (Owner ID: {})", event.getGuild(), event.getGuild().getOwner().getId());
                return;
            }

            //Post bot statistics to the main API.
            this.updateStats(jda);

            Metrics.GUILD_ACTIONS.labels("leave").inc();
            MantaroBot.getInstance().getAudioManager().getMusicManagers().remove(event.getGuild().getId());
        } catch (Exception e) {
            if (!(e instanceof NullPointerException) && !(e instanceof IllegalArgumentException)) {
                log.error("Unexpected error while logging an event", e);
            }
        }
    }

    private void onUserJoin(GuildMemberJoinEvent event) {
        final var guild = event.getGuild();
        final var dbGuild = MantaroData.db().getGuild(guild);
        final var guildData = dbGuild.getData();
        final var user = event.getUser();

        try {
            var role = guildData.getGuildAutoRole();

            final var hour = Utils.formatHours(OffsetDateTime.now(), guildData.getLang());
            if (role != null) {
                try {
                    if (!(user.isBot() && guildData.isIgnoreBotsAutoRole())) {
                        var toAssign = guild.getRoleById(role);
                        if (toAssign != null) {
                            if (guild.getSelfMember().canInteract(toAssign)) {
                                guild.addRoleToMember(event.getMember(), toAssign)
                                        .reason("Autorole assigner.")
                                        .queue(s -> log.debug("Successfully added a new role to " + event.getMember()));

                                Metrics.ACTIONS.labels("join_autorole").inc();
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }

            var logChannel = guildData.getGuildLogChannel();
            if (logChannel != null) {
                var tc = guild.getTextChannelById(logChannel);
                if (tc != null && tc.canTalk()) {
                    tc.sendMessage(String.format("`[%s]` \uD83D\uDCE3 `%s#%s` just joined `%s` `(ID: %s)`",
                            hour, event.getUser().getName(), event.getUser().getDiscriminator(),
                            guild.getName(), event.getUser().getId())
                    ).queue();

                    logTotal++;
                }
            }
        } catch (Exception e) {
            log.error("Failed to process log join message!", e);
        }

        try {
            if (user.isBot() && guildData.isIgnoreBotsWelcomeMessage()) {
                return;
            }

            var joinChannel = guildData.getLogJoinChannel();
            if (joinChannel == null || guild.getTextChannelById(joinChannel) == null) {
                joinChannel = guildData.getLogJoinLeaveChannel();
            }

            if (joinChannel == null) {
                return;
            }

            var joinMessage = guildData.getJoinMessage();
            sendJoinLeaveMessage(event.getUser(), guild,
                    guild.getTextChannelById(joinChannel), guildData.getExtraJoinMessages(), joinMessage
            );

            Metrics.ACTIONS.labels("join_messages").inc();
        } catch (Exception e) {
            log.error("Failed to send join message!", e);
        }
    }

    private void onUserLeave(GuildMemberRemoveEvent event) {
        final var guild = event.getGuild();
        final var user = event.getUser();
        final var dbGuild = MantaroData.db().getGuild(guild);
        final var guildData = dbGuild.getData();

        try {
            final var hour = Utils.formatHours(OffsetDateTime.now(), guildData.getLang());
            if (user.isBot() && guildData.isIgnoreBotsWelcomeMessage()) {
                return;
            }

            var logChannel = guildData.getGuildLogChannel();
            if (logChannel != null) {
                TextChannel tc = guild.getTextChannelById(logChannel);
                if (tc != null && tc.canTalk()) {
                    tc.sendMessage(String.format("`[%s]` \uD83D\uDCE3 `%s#%s` just left `%s` `(ID: %s)`",
                            hour, user.getName(), user.getDiscriminator(),
                            guild.getName(), user.getId())
                    ).queue();
                    logTotal++;
                }
            }
        } catch (Exception e) {
            log.error("Failed to process log leave message!", e);
        }

        try {
            if (user.isBot() && guildData.isIgnoreBotsWelcomeMessage()) {
                return;
            }

            var leaveChannel = guildData.getLogLeaveChannel();
            if (leaveChannel == null || guild.getTextChannelById(leaveChannel) == null) {
                leaveChannel = guildData.getLogJoinLeaveChannel();
            }

            if (leaveChannel == null) {
                return;
            }

            var leaveMessage = guildData.getLeaveMessage();
            sendJoinLeaveMessage(user, guild,
                    guild.getTextChannelById(leaveChannel), guildData.getExtraLeaveMessages(), leaveMessage
            );

            Metrics.ACTIONS.labels("leave_messages").inc();
        } catch (Exception e) {
            log.error("Failed to send leave message!", e);
        }

        var allowedBirthdays = guildData.getAllowedBirthdays();
        if (allowedBirthdays.contains(user.getId())) {
            allowedBirthdays.remove(user.getId());
            dbGuild.saveAsync();

            var bdCacheMap = BirthdayCmd.getGuildBirthdayCache().getIfPresent(guild.getId());
            if (bdCacheMap != null) {
                bdCacheMap.remove(user.getId());
            }
        }
    }

    private void sendJoinLeaveMessage(User user, Guild guild, TextChannel tc, List<String> extraMessages, String msg) {
        var select = extraMessages.isEmpty() ? 0 : rand.nextInt(extraMessages.size());
        var message = rand.nextBoolean() ? msg : extraMessages.isEmpty() ? msg : extraMessages.get(select);

        if (tc != null && message != null) {
            if (!tc.canTalk()) {
                return;
            }

            if (message.contains("$(")) {
                message = new DynamicModifiers()
                        .mapFromJoinLeave("event", tc, user, guild)
                        .resolve(message);
            }

            var c = message.indexOf(':');
            if (c != -1) {
                //Wonky?
                var matcher = modifierPattern.matcher(message);
                var m = "none";
                //Find the first occurrence of a modifier (word:)
                if (matcher.find()) {
                    m = matcher.group().replace(":", "");
                }

                var v = message.substring(c + 1);
                var r = message.substring(0, c - m.length()).trim();

                try {
                    if (m.equals("embed")) {
                        EmbedJSON embed;
                        try {
                            embed = JsonDataManager.fromJson('{' + v + '}', EmbedJSON.class);
                        } catch (Exception e) {
                            tc.sendMessage(EmoteReference.ERROR2 +
                                    "The string\n```json\n{" + v + "}```\nIs not a valid JSON (failed to Convert to EmbedJSON).").queue();
                            e.printStackTrace();
                            return;
                        }

                        var builder = new MessageBuilder().setEmbed(embed.gen(null));

                        if (!r.isEmpty())
                            builder.append(r);

                        tc.sendMessage(builder.build())
                                .allowedMentions(EnumSet.of(Message.MentionType.USER, Message.MentionType.ROLE))
                                .queue(success -> { }, error -> tc.sendMessage("Failed to send join/leave message.").queue()
                        );

                        return;
                    }
                } catch (Exception e) {
                    if (e.getMessage().toLowerCase().contains("url must be a valid")) {
                        tc.sendMessage(
                                "Failed to send join/leave message: Wrong image URL in thumbnail, image, footer and/or author."
                        ).queue();
                    } else {
                        tc.sendMessage(
                                "Failed to send join/leave message: Unknown error, try checking your message."
                        ).queue();

                        e.printStackTrace();
                    }
                }
            }

            tc.sendMessage(message)
                    .allowedMentions(EnumSet.of(Message.MentionType.USER, Message.MentionType.ROLE))
                    .queue(success -> { }, failure ->
                            tc.sendMessage("Failed to send join/leave message.").queue()
                    );
        }
    }

    private void updateStats(JDA jda) {
        if (jda.getStatus() == JDA.Status.INITIALIZED) {
            return;
        }

        try(var jedis = MantaroData.getDefaultJedisPool().getResource()) {
            var json = new JSONObject()
                    .put("guild_count", jda.getGuildCache().size())
                    .put("cached_users", jda.getUserCache().size())
                    .put("gateway_ping", jda.getGatewayPing())
                    .put("shard_status", jda.getStatus())
                    .put("last_ping_diff", ((MantaroEventManager) jda.getEventManager()).lastJDAEventDiff())
                    .put("node_number", MantaroBot.getInstance().getNodeNumber())
                    .toString();

            jedis.hset("shardstats-" + config.getClientId(),
                    String.valueOf(jda.getShardInfo().getShardId()), json
            );

            log.debug("Sent process shard stats to redis -> {}", json);
        }
    }
}
