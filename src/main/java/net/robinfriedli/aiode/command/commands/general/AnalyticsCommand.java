package net.robinfriedli.aiode.command.commands.general;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

import com.google.common.base.Strings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.audio.AudioManager;
import net.robinfriedli.aiode.audio.AudioPlayback;
import net.robinfriedli.aiode.boot.SpringPropertiesConfig;
import net.robinfriedli.aiode.command.AbstractCommand;
import net.robinfriedli.aiode.command.CommandContext;
import net.robinfriedli.aiode.command.CommandManager;
import net.robinfriedli.aiode.discord.GuildManager;
import net.robinfriedli.aiode.entities.CommandHistory;
import net.robinfriedli.aiode.entities.PlaybackHistory;
import net.robinfriedli.aiode.entities.Playlist;
import net.robinfriedli.aiode.entities.Song;
import net.robinfriedli.aiode.entities.UrlTrack;
import net.robinfriedli.aiode.entities.Video;
import net.robinfriedli.aiode.entities.xml.CommandContribution;
import org.hibernate.Session;

public class AnalyticsCommand extends AbstractCommand {

    public AnalyticsCommand(CommandContribution commandContribution, CommandContext context, CommandManager commandManager, String commandString, boolean requiresInput, String identifier, String description, Category category) {
        super(commandContribution, context, commandManager, commandString, requiresInput, identifier, description, category);
    }

    @Override
    public void doRun() {
        ShardManager shardManager = Aiode.get().getShardManager();
        List<Guild> guilds = shardManager.getGuilds();
        Aiode aiode = Aiode.get();
        AudioManager audioManager = aiode.getAudioManager();
        GuildManager guildManager = aiode.getGuildManager();
        SpringPropertiesConfig springPropertiesConfig = aiode.getSpringPropertiesConfig();
        Session session = getContext().getSession();
        Runtime runtime = Runtime.getRuntime();

        int guildCount = guilds.size();
        long playingCount = guilds.stream().map(audioManager::getPlaybackForGuild).filter(AudioPlayback::isPlaying).count();
        long commandCount = session.createQuery("select count(*) from " + CommandHistory.class.getName(), Long.class).uniqueResult();
        long playlistCount = session.createQuery("select count(*) from " + Playlist.class.getName(), Long.class).uniqueResult();
        long trackCount = session.createQuery("select count(*) from " + Song.class.getName(), Long.class).uniqueResult()
            + session.createQuery("select count(*) from " + Video.class.getName(), Long.class).uniqueResult()
            + session.createQuery("select count(*) from " + UrlTrack.class.getName(), Long.class).uniqueResult();
        long playedCount = session.createQuery("select count(*) from " + PlaybackHistory.class.getName(), Long.class).uniqueResult();
        // convert to MB by right shifting by 20 bytes (same as dividing by 2^20)
        long maxMemory = runtime.maxMemory() >> 20;
        long allocatedMemory = runtime.totalMemory() >> 20;
        long unallocatedMemory = maxMemory - allocatedMemory;
        long allocFreeMemory = runtime.freeMemory() >> 20;
        long usedMemory = allocatedMemory - allocFreeMemory;
        long totalFreeMemory = maxMemory - usedMemory;

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadMXBean.getThreadCount();
        int daemonThreadCount = threadMXBean.getDaemonThreadCount();

        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.addField("Guilds", String.valueOf(guildCount), true);
        embedBuilder.addField("Guilds active", String.valueOf(guildManager.getActiveGuilds(session).size()), true);
        embedBuilder.addField("Guilds playing now", String.valueOf(playingCount), true);
        embedBuilder.addField("Total commands entered", String.valueOf(commandCount), true);
        embedBuilder.addField("Saved playlists", String.valueOf(playlistCount), true);
        embedBuilder.addField("Saved tracks", String.valueOf(trackCount), true);
        embedBuilder.addField("Total tracks played", String.valueOf(playedCount), true);
        embedBuilder.addField("Thread count", String.format("%d (%d daemons)", threadCount, daemonThreadCount), true);

        String shardRange = springPropertiesConfig.getApplicationProperty("aiode.preferences.shard_range");
        if (!Strings.isNullOrEmpty(shardRange)) {
            embedBuilder.addField("Shards", shardRange, true);
        }

        embedBuilder.addField("Memory (in MB)",
            "Total: " + maxMemory + System.lineSeparator() +
                "Allocated: " + allocatedMemory + System.lineSeparator() +
                "Unallocated: " + unallocatedMemory + System.lineSeparator() +
                "Free allocated: " + allocFreeMemory + System.lineSeparator() +
                "Currently used: " + usedMemory + System.lineSeparator() +
                "Total free: " + totalFreeMemory
            , false);

        sendWithLogo(embedBuilder);
    }

    @Override
    public void onSuccess() {
    }
}
