package net.robinfriedli.aiode.audio;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.discord.property.GuildPropertyManager;
import net.robinfriedli.aiode.entities.GuildSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.lava.extensions.youtuberotator.YoutubeIpRotatorSetup;
import com.sedmelluq.lava.extensions.youtuberotator.planner.RotatingNanoIpRoutePlanner;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.IpBlock;
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.VoiceChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.robinfriedli.aiode.audio.exec.TrackLoadingExecutor;
import net.robinfriedli.aiode.audio.spotify.SpotifyService;
import net.robinfriedli.aiode.audio.youtube.YouTubeService;
import net.robinfriedli.aiode.boot.AbstractShutdownable;
import net.robinfriedli.aiode.boot.configurations.HibernateComponent;
import net.robinfriedli.aiode.command.widget.WidgetRegistry;
import net.robinfriedli.aiode.command.widget.widgets.NowPlayingWidget;
import net.robinfriedli.aiode.concurrent.CompletableFutures;
import net.robinfriedli.aiode.concurrent.ExecutionContext;
import net.robinfriedli.aiode.concurrent.HistoryPool;
import net.robinfriedli.aiode.discord.GuildContext;
import net.robinfriedli.aiode.discord.GuildManager;
import net.robinfriedli.aiode.entities.PlaybackHistory;
import net.robinfriedli.aiode.entities.UserPlaybackHistory;
import net.robinfriedli.aiode.exceptions.InvalidCommandException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provides access to all {@link AudioPlayback} for all guilds and methods to retrieve all audio related factories and
 * services, such as the {@link PlayableFactory} or {@link YouTubeService}, or methods to start a playback or
 * join / leave voice channels. Also manages the playback history and creates the {@link NowPlayingWidget}
 */
@Component
public class AudioManager extends AbstractShutdownable {

    private final AudioPlayerManager playerManager;
    private final AudioTrackLoader audioTrackLoader;
    private final GuildManager guildManager;
    private final HibernateComponent hibernateComponent;
    private final Logger logger;
    private final YouTubeService youTubeService;

    public AudioManager(GuildManager guildManager,
                        HibernateComponent hibernateComponent,
                        YouTubeService youTubeService,
                        @Value("${aiode.preferences.ipv6_blocks}") String ipv6Blocks) {
        playerManager = new DefaultAudioPlayerManager();
        audioTrackLoader = new AudioTrackLoader(playerManager);

        this.guildManager = guildManager;
        this.hibernateComponent = hibernateComponent;
        this.logger = LoggerFactory.getLogger(getClass());
        this.youTubeService = youTubeService;

        AudioSourceManagers.registerRemoteSources(playerManager);
        guildManager.setAudioManager(this);
        YoutubeAudioSourceManager youtubeAudioSourceManager = playerManager.source(YoutubeAudioSourceManager.class);
        // there is 100 videos per page and the maximum playlist size is 5000
        youtubeAudioSourceManager.setPlaylistPageCount(50);

        if (!Strings.isNullOrEmpty(ipv6Blocks)) {
            // NanoIpRoutePlanner uses raw type
            @SuppressWarnings("rawtypes")
            List<IpBlock> ipv6BlockList = Splitter.on(",")
                .trimResults()
                .omitEmptyStrings()
                .splitToList(ipv6Blocks)
                .stream()
                .map(Ipv6Block::new)
                .collect(Collectors.toList());

            YoutubeIpRotatorSetup youtubeIpRotatorSetup = new YoutubeIpRotatorSetup(new RotatingNanoIpRoutePlanner(ipv6BlockList, ip -> true, true));
            youtubeIpRotatorSetup.forSource(youtubeAudioSourceManager).setup();
            logger.info("YouTubeIpRotator set up with block: " + ipv6Blocks);
        }
    }

    public void startPlayback(Guild guild, @Nullable VoiceChannel channel) {
        playTrack(guild, channel, false);
    }

    public void startOrResumePlayback(Guild guild, @Nullable VoiceChannel channel) {
        playTrack(guild, channel, true);
    }

    public void playTrack(Guild guild, @Nullable VoiceChannel channel, boolean resumePaused) {
        AudioPlayback playback = getPlaybackForGuild(guild);

        if (!resumePaused && playback.isPaused()) {
            playback.getAudioPlayer().stopTrack();
            playback.unpause();
        }

        if (channel != null) {
            setChannel(playback, channel);
        } else if (playback.getVoiceChannel() == null) {
            throw new InvalidCommandException("Not in a voice channel");
        }

        if (ExecutionContext.Current.isSet()) {
            playback.setCommunicationChannel(ExecutionContext.Current.require().getChannel());
        }

        if (playback.isPaused() && resumePaused) {
            playback.unpause();
        } else {
            QueueIterator queueIterator = new QueueIterator(playback, this);
            playback.setCurrentQueueIterator(queueIterator);
            queueIterator.playNext();
        }
    }

    public AudioPlayback getPlaybackForGuild(Guild guild) {
        return guildManager.getContextForGuild(guild).getPlayback();
    }

    public AudioQueue getQueue(Guild guild) {
        return getPlaybackForGuild(guild).getAudioQueue();
    }

    public YouTubeService getYouTubeService() {
        return youTubeService;
    }

    public AudioPlayerManager getPlayerManager() {
        return playerManager;
    }

    public PlayableFactory createPlayableFactory(SpotifyService spotifyService, TrackLoadingExecutor trackLoadingExecutor) {
        return new PlayableFactory(audioTrackLoader, spotifyService, trackLoadingExecutor, youTubeService);
    }

    void createHistoryEntry(Playable playable, Guild guild, VoiceChannel voiceChannel) {
        HistoryPool.execute(() -> {
            try {
                hibernateComponent.consumeSession(session -> {
                    PlaybackHistory playbackHistory = new PlaybackHistory(LocalDateTime.now(), playable, guild, session);

                    session.persist(playbackHistory);
                    if (voiceChannel != null) {
                        Member selfMember = guild.getSelfMember();
                        for (Member member : voiceChannel.getMembers()) {
                            if (!member.equals(selfMember)) {
                                UserPlaybackHistory userPlaybackHistory = new UserPlaybackHistory(member.getUser(), playbackHistory);
                                session.persist(userPlaybackHistory);
                            }
                        }
                    }

                });
            } catch (Exception e) {
                logger.error("Exception while creating playback history entry", e);
            }
        });
    }

    void createNowPlayingWidget(CompletableFuture<Message> futureMessage, AudioPlayback playback) {
        Guild guild = playback.getGuild();
        WidgetRegistry widgetRegistry = guildManager.getContextForGuild(guild).getWidgetRegistry();
        CompletableFutures.thenAccept(futureMessage, message -> new NowPlayingWidget(widgetRegistry, guild, message).initialise());
    }

    private void setChannel(AudioPlayback audioPlayback, VoiceChannel channel) {
        audioPlayback.setVoiceChannel(channel);
        Guild guild = audioPlayback.getGuild();
        guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(audioPlayback.getAudioPlayer()));
        try {
            guild.getAudioManager().openAudioConnection(channel);
            audioPlayback.setVolume(getDefaultVolume(guild));
        } catch (InsufficientPermissionException e) {
            throw new InvalidCommandException("I do not have permission to join this voice channel!");
        }
    }

    private int getDefaultVolume(Guild guild) {
        return hibernateComponent.invokeWithSession(session -> {
            GuildPropertyManager guildPropertyManager = Aiode.get().getGuildPropertyManager();
            GuildManager guildManager = Aiode.get().getGuildManager();
            GuildSpecification specification = guildManager.getContextForGuild(guild).getSpecification(session);

            return guildPropertyManager
                    .getPropertyValueOptional("defaultVolume", Integer.class, specification)
                    .orElse(100);
        });
    }

    @Override
    public void shutdown(int delayMs) {
        for (GuildContext guildContext : guildManager.getGuildContexts()) {
            guildContext.getPlayback().stop();
        }
        playerManager.shutdown();
    }
}
