package net.robinfriedli.botify.audio;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemListResponse;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.wrapper.spotify.model_objects.specification.ArtistSimplified;
import com.wrapper.spotify.model_objects.specification.Track;
import net.robinfriedli.botify.command.commands.PlayCommand;
import net.robinfriedli.botify.command.commands.QueueCommand;
import net.robinfriedli.botify.exceptions.NoResultsFoundException;
import net.robinfriedli.stringlist.StringList;
import net.robinfriedli.stringlist.StringListImpl;

public class YouTubeService {

    private final YouTube youTube;
    private final String apiKey;

    public YouTubeService(YouTube youTube, String apiKey) {
        this.youTube = youTube;
        this.apiKey = apiKey;
    }

    /**
     * Workaround as Spotify does not allow full playback of tracks via third party APIs using the web api for licencing
     * reasons. Gets the metadata and searches the corresponding YouTube video. The only way to stream from Spotify
     * directly is by using the $preview argument with the {@link PlayCommand} or {@link QueueCommand} which plays the
     * provided mp3 preview.
     *
     * However Spotify MIGHT release an SDK supporting full playback of songs across all devices, not just browsers in
     * which case this method and the corresponding black in {@link AudioManager#createPlayable(boolean, Object)} should
     * be removed.
     * For reference, see <a href="https://github.com/spotify/web-api/issues/57">Web playback of Full Tracks - Github</a>
     *
     * @param spotifyTrack the spotify track
     * @return the URL for the matching YouTube video
     */
    public YouTubeVideo redirectSpotify(Track spotifyTrack) {
        try {
            YouTube.Search.List search = youTube.search().list("id,snippet");
            search.setKey(apiKey);

            StringList artists = StringListImpl.create(spotifyTrack.getArtists(), ArtistSimplified::getName);
            String searchTerm = spotifyTrack.getName() + " " + artists.toSeparatedString(" ");
            search.setQ(searchTerm);
            // set topic to filter results to music video
            search.setTopicId("/m/04rlf");
            search.setType("video");
            search.setFields("items(snippet/title,id/videoId)");
            search.setMaxResults(1L);

            List<SearchResult> items = search.execute().getItems();
            if (items.isEmpty()) {
                throw new NoResultsFoundException("No YouTube Video found for track " + searchTerm);
            }
            SearchResult searchResult = items.get(0);
            String videoId = searchResult.getId().getVideoId();
            long durationMillis = getDurationMillis(videoId);

            String artistString = artists.toSeparatedString(", ");
            return new YouTubeVideoImpl(spotifyTrack.getName() + " by " + artistString, videoId, durationMillis, spotifyTrack);
        } catch (IOException e) {
            throw new RuntimeException("Exception occurred during YouTube redirect", e);
        }
    }

    public YouTubeVideo searchVideo(String searchTerm) {
        try {
            YouTube.Search.List search = youTube.search().list("id,snippet");
            search.setQ(searchTerm);
            search.setType("video");
            search.setFields("items(snippet/title,id/videoId)");
            search.setMaxResults(1L);
            search.setKey(apiKey);

            List<SearchResult> items = search.execute().getItems();
            if (items.isEmpty()) {
                throw new NoResultsFoundException("No YouTube Video found for " + searchTerm);
            }
            SearchResult searchResult = items.get(0);
            String videoId = searchResult.getId().getVideoId();
            String title = searchResult.getSnippet().getTitle();
            long durationMillis = getDurationMillis(videoId);

            return new YouTubeVideoImpl(title, videoId, durationMillis);
        } catch (IOException e) {
            throw new RuntimeException("Exception occurred during YouTube search", e);
        }
    }

    public YouTubePlaylist searchPlaylist(String searchTerm) {
        try {
            YouTube.Search.List playlistSearch = youTube.search().list("id,snippet");
            playlistSearch.setKey(apiKey);
            playlistSearch.setQ(searchTerm);
            playlistSearch.setType("playlist");
            playlistSearch.setFields("items(id/playlistId,snippet/title,snippet/channelTitle)");
            playlistSearch.setMaxResults(1L);

            List<SearchResult> items = playlistSearch.execute().getItems();
            if (items.isEmpty()) {
                throw new NoResultsFoundException("No YouTube playlist found for " + searchTerm);
            }
            SearchResult searchResult = items.get(0);
            String playlistId = searchResult.getId().getPlaylistId();
            String title = searchResult.getSnippet().getTitle();
            String channelTitle = searchResult.getSnippet().getChannelTitle();

            int itemCount = youTube
                .playlists()
                .list("contentDetails")
                .setKey(apiKey)
                .setId(playlistId)
                .setFields("items(contentDetails/itemCount)")
                .setMaxResults(1L)
                .execute()
                .getItems()
                .get(0)
                .getContentDetails()
                .getItemCount()
                .intValue();

            // return hollow youtube videos so that the playlist items can be loaded asynchronously
            List<HollowYouTubeVideo> videos = Lists.newArrayListWithCapacity(itemCount);
            for (int i = 0; i < itemCount; i++) {
                videos.add(new HollowYouTubeVideo());
            }

            return new YouTubePlaylist(title, playlistId, channelTitle, videos);
        } catch (IOException e) {
            throw new RuntimeException("Exception occurred during YouTube search", e);
        }
    }

    public void populateList(YouTubePlaylist playlist) {
        try {
            YouTube.PlaylistItems.List itemSearch = youTube.playlistItems().list("snippet");
            itemSearch.setKey(apiKey);
            itemSearch.setMaxResults(50L);
            itemSearch.setFields("items(snippet/title,snippet/resourceId),nextPageToken");
            itemSearch.setPlaylistId(playlist.getId());

            String nextPageToken;
            List<PlaylistItem> playlistItems = Lists.newArrayList();
            List<HollowYouTubeVideo> hollowVideos = playlist.getVideos();
            int index = 0;
            do {
                PlaylistItemListResponse response = itemSearch.execute();
                nextPageToken = response.getNextPageToken();
                List<PlaylistItem> items = response.getItems();
                playlistItems.addAll(items);

                List<String> videoIds = items.stream().map(i -> i.getSnippet().getResourceId().getVideoId()).collect(Collectors.toList());
                Map<String, Long> durationMap = getDurationMillis(videoIds);

                for (PlaylistItem item : items) {
                    String videoTitle = item.getSnippet().getTitle();
                    String videoId = item.getSnippet().getResourceId().getVideoId();
                    Long duration = durationMap.get(videoId);
                    HollowYouTubeVideo hollowVideo = hollowVideos.get(index);
                    hollowVideo.setTitle(videoTitle);
                    hollowVideo.setId(videoId);
                    hollowVideo.setDuration(duration != null ? duration : 0);
                    ++index;
                }

                itemSearch.setPageToken(nextPageToken);
            } while (!Strings.isNullOrEmpty(nextPageToken));

            if (playlistItems.isEmpty()) {
                throw new NoResultsFoundException("Playlist " + playlist.getTitle() + " has no items");
            }
        } catch (IOException e) {
            throw new RuntimeException("Exception occurred while loading playlist items", e);
        }
    }

    /**
     * Calls the video source to retrieve its duration in milliseconds
     *
     * @param videoId the id of the video
     * @return the video's duration in milliseconds
     */
    private long getDurationMillis(String videoId) throws IOException {
        YouTube.Videos.List videosRequest = youTube.videos().list("contentDetails");
        videosRequest.setKey(apiKey);
        videosRequest.setId(videoId);
        videosRequest.setFields("items(contentDetails/duration)");
        VideoListResponse videoListResponse = videosRequest.execute();
        List<Video> items = videoListResponse.getItems();
        if (items.size() == 1) {
            String iso8601Duration = items.get(0).getContentDetails().getDuration();
            // ChronoUnit.MILLIS not supported because of the accuracy YouTube returns
            return Duration.parse(iso8601Duration).get(ChronoUnit.SECONDS) * 1000;
        } else {
            // video detail might not get found if the video is unavailable
            return 0;
        }
    }

    private Map<String, Long> getDurationMillis(Collection<String> videoIds) throws IOException {
        if (videoIds.size() > 50) {
            throw new IllegalArgumentException("Cannot request more than 50 ids at once");
        }

        YouTube.Videos.List videosRequest = youTube.videos().list("contentDetails");
        videosRequest.setKey(apiKey);
        videosRequest.setId(String.join(",", videoIds));
        videosRequest.setFields("items(contentDetails/duration,id)");
        List<Video> videos = videosRequest.execute().getItems();

        Map<String, Long> durationMap = new HashMap<>();
        for (Video video : videos) {
            String iso8601Duration = video.getContentDetails().getDuration();
            String id = video.getId();
            durationMap.put(id, Duration.parse(iso8601Duration).get(ChronoUnit.SECONDS) * 1000);
        }

        return durationMap;
    }

}
