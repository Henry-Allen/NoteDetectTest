package org.openjfx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TabPrototypeController {

    private static final String SONGSTERR_SEARCH_ENDPOINT = "https://www.songsterr.com/api/songs?pattern=";
    private static final String SONGSTERR_VIEW_BASE = "https://www.songsterr.com/a/wsa/";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, new SongsterrThreadFactory());
    private static final String USER_AGENT = "NoteDetectTest/1.0 (+https://github.com)";
    private static final String[] PART_CDN_HOSTS = {
            "d3rrfvx08uyjp1",
            "dodkcbujl0ebx",
            "dj1usja78sinh"
    };

    private final ObservableList<SongItem> songs = FXCollections.observableArrayList();
    private final ObservableList<TrackItem> tracks = FXCollections.observableArrayList();
    private final Map<Integer, SongDetails> songDetailsCache = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<SongDetails>> songDetailsInFlight = new ConcurrentHashMap<>();

    private CompletableFuture<SongDetails> currentSongDetailsFuture;
    private volatile int activeSongId = -1;

    @FXML private TextField searchField;
    @FXML private Button searchButton;
    @FXML private ListView<SongItem> songsList;
    @FXML private ListView<TrackItem> tracksList;
    @FXML private TextArea tabDisplay;
    @FXML private Button openBrowserButton;
    @FXML private Label statusLabel;

    @FXML
    private void initialize() {
        songsList.setItems(songs);
        songsList.setCellFactory(simpleCell(SongItem::displayLabel));
        songsList.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            tracks.clear();
            tracksList.getSelectionModel().clearSelection();
            tabDisplay.clear();
            if (newItem != null) {
                activeSongId = newItem.songId();
                tracks.addAll(newItem.tracks());
                setStatus("Fetching Songsterr metadata...", false);
                currentSongDetailsFuture = getSongDetails(newItem);
                currentSongDetailsFuture.whenComplete((details, error) -> Platform.runLater(() -> {
                    if (songsList.getSelectionModel().getSelectedItem() != newItem) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        setStatus("Failed to load song metadata: " + cause.getMessage(), true);
                    } else if (details != null) {
                        applySongDetailsToTracks(newItem, details);
                        setStatus("Song metadata loaded. Select a track to scrape tab JSON.", false);
                    }
                }));
            } else {
                activeSongId = -1;
                currentSongDetailsFuture = null;
            }
        });

        if (openBrowserButton != null) {
            openBrowserButton.setDisable(true);
        }

        tracksList.setItems(tracks);
        tracksList.setCellFactory(simpleCell(TrackItem::displayLabel));
        tracksList.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (openBrowserButton != null) {
                openBrowserButton.setDisable(newItem == null);
            }
            if (newItem != null) {
                tabDisplay.setText(buildPreview(newItem));
                loadTrackJson(newItem);
            } else {
                tabDisplay.clear();
            }
        });

        searchField.setOnAction(event -> onSearch());
        setStatus("Enter a search term to begin.", false);
    }

    @FXML
    private void onSearch() {
        String term = searchField.getText();
        if (term == null || term.trim().isEmpty()) {
            setStatus("Please enter a search term.", true);
            return;
        }

        final String cleanedTerm = term.trim();
        searchButton.setDisable(true);
        songs.clear();
        tracks.clear();
        tabDisplay.clear();
        setStatus("Searching Songsterr for \"" + cleanedTerm + "\"...", false);

        CompletableFuture
                .supplyAsync(() -> fetchSongs(cleanedTerm), EXECUTOR)
                .whenComplete((results, error) -> Platform.runLater(() -> {
                    searchButton.setDisable(false);
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        setStatus("Search failed: " + cause.getMessage(), true);
                        return;
                    }
                    if (results == null || results.isEmpty()) {
                        setStatus("No matches found. Try a different query.", false);
                        return;
                    }
                    songs.setAll(results);
                    setStatus("Found " + results.size() + " song(s). Pick one, then choose a track to view its scraped tab JSON.", false);
                }));
    }

    private void loadTrackJson(TrackItem track) {
        if (track == null) return;
        CompletableFuture<SongDetails> detailsFuture = currentSongDetailsFuture;
        if (detailsFuture == null) {
            setStatus("Song metadata is still loading. Please wait...", true);
            return;
        }

        if (track.prettyTabJson() != null) {
            tabDisplay.setText(buildPreview(track));
            setStatus("Loaded cached tab JSON. Use \"Open in Browser\" to view the live version.", false);
            return;
        }

        setStatus("Scraping tab JSON for \"" + track.name() + "\"...", false);

        detailsFuture
                .thenCompose(details -> {
                    if (details == null || details.songId != track.songId()) {
                        throw new IllegalStateException("Song changed while loading tab data.");
                    }
                    SongTrackInfo info = details.trackForHash(track.hash());
                    if (info == null) {
                        throw new IllegalStateException("Songsterr did not return part info for this track.");
                    }
                    if (track.prettyTabJson() != null) {
                        return CompletableFuture.completedFuture(track.prettyTabJson());
                    }
                    return fetchTabJson(details, info).thenApply(raw -> {
                        String pretty = prettifyJson(raw);
                        track.setTabJson(raw, pretty);
                        return pretty;
                    });
                })
                .whenComplete((prettyJson, error) -> Platform.runLater(() -> {
                    if (tracksList.getSelectionModel().getSelectedItem() != track) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        setStatus("Failed to scrape tab data: " + cause.getMessage(), true);
                    } else if (prettyJson != null) {
                        tabDisplay.setText(buildTabDisplay(track, prettyJson));
                        setStatus("Loaded tab JSON. Use \"Open in Browser\" to view the live version.", false);
                    }
                }));
    }

    @FXML
    private void onOpenInBrowser() {
        TrackItem track = tracksList.getSelectionModel().getSelectedItem();
        if (track == null) {
            setStatus("Select a track to open in your browser.", true);
            return;
        }
        openTrackInBrowser(track);
    }

    private CompletableFuture<SongDetails> getSongDetails(SongItem song) {
        SongDetails cached = songDetailsCache.get(song.songId());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return songDetailsInFlight.computeIfAbsent(song.songId(), id ->
                CompletableFuture.supplyAsync(() -> fetchSongDetails(song), EXECUTOR)
                        .whenComplete((details, error) -> {
                            if (error == null && details != null) {
                                songDetailsCache.put(id, details);
                            }
                            songDetailsInFlight.remove(id);
                        })
        );
    }

    private SongDetails fetchSongDetails(SongItem song) {
        try {
            String url = buildSongUrl(song.artist(), song.title(), song.songId());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Song view returned HTTP " + response.statusCode());
            }
            JsonNode state = extractStateJson(response.body());
            JsonNode meta = state.path("meta").path("current");
            int revisionId = meta.path("revisionId").asInt(-1);
            if (revisionId <= 0) {
                throw new IOException("Songsterr response missing revision id.");
            }
            Map<String, SongTrackInfo> trackMap = new HashMap<>();
            JsonNode tracksNode = meta.path("tracks");
            if (tracksNode.isArray()) {
                int index = 0;
                for (JsonNode trackNode : tracksNode) {
                    String hash = trackNode.path("hash").asText(null);
                    int partId = trackNode.path("partId").asInt(-1);
                    if (hash != null && partId >= 0) {
                        trackMap.put(hash, new SongTrackInfo(partId, index));
                    }
                    index++;
                }
            }
            SongDetails details = new SongDetails(song.songId(), revisionId, trackMap);
            System.out.println("[Songsterr][details] songId=" + song.songId() + " revisionId=" + revisionId + " tracks=" + trackMap.size());
            return details;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading song details.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private void applySongDetailsToTracks(SongItem song, SongDetails details) {
        for (TrackItem track : song.tracks()) {
            SongTrackInfo info = details.trackForHash(track.hash());
            if (info != null) {
                track.setSongMeta(info.partId, info.index);
            }
        }
        if (songsList.getSelectionModel().getSelectedItem() == song) {
            tracksList.refresh();
            TrackItem selected = tracksList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                tabDisplay.setText(buildPreview(selected));
            }
        }
    }

    private CompletableFuture<String> fetchTabJson(SongDetails details, SongTrackInfo info) {
        List<URI> candidates = buildCandidateUris(details, info);
        CompletableFuture<String> future = new CompletableFuture<>();
        fetchTabCandidate(candidates, 0, future);
        return future;
    }

    private List<URI> buildCandidateUris(SongDetails details, SongTrackInfo info) {
        List<URI> uris = new ArrayList<>();
        for (String host : PART_CDN_HOSTS) {
            String url = String.format("https://%s.cloudfront.net/part/%d/%d", host, details.revisionId, info.partId);
            uris.add(URI.create(url));
        }
        return uris;
    }

    private void fetchTabCandidate(List<URI> uris, int index, CompletableFuture<String> future) {
        if (future.isDone()) return;
        if (index >= uris.size()) {
            future.completeExceptionally(new IOException("No Songsterr tab sources responded."));
            return;
        }
        URI uri = uris.get(index);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, error) -> {
                    if (future.isDone()) {
                        return;
                    }
                    if (error != null) {
                        System.out.println("[Songsterr][scrape] " + uri + " error=" + error.getMessage());
                        fetchTabCandidate(uris, index + 1, future);
                    } else if (response.statusCode() == 200) {
                        String body = response.body();
                        System.out.println("[Songsterr][scrape] " + uri + " status=200 bytes=" + (body != null ? body.length() : 0));
                        future.complete(body);
                    } else {
                        System.out.println("[Songsterr][scrape] " + uri + " status=" + response.statusCode());
                        fetchTabCandidate(uris, index + 1, future);
                    }
                });
    }

    @FXML
    private void switchToPrimary() {
        try {
            App.setRoot("primary");
        } catch (IOException ex) {
            setStatus("Failed to return to primary view: " + ex.getMessage(), true);
        }
    }

    private List<SongItem> fetchSongs(String term) {
        try {
            String encoded = URLEncoder.encode(term, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(SONGSTERR_SEARCH_ENDPOINT + encoded))
                    .header("Accept", "application/json")
                    .header("User-Agent", "NoteDetectTest/1.0 (+https://github.com)")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body();
            System.out.println("[Songsterr][search] term=\"" + term + "\" status=" + response.statusCode() + " body=" + body);
            if (response.statusCode() != 200) {
                throw new IOException("Songsterr returned HTTP " + response.statusCode());
            }

            JsonNode root = MAPPER.readTree(body);
            if (!root.isArray()) {
                return Collections.emptyList();
            }

            List<SongItem> items = new ArrayList<>();
            for (JsonNode node : root) {
                int songId = node.path("songId").asInt(-1);
                if (songId <= 0) continue;
                String artist = node.path("artist").asText("Unknown Artist");
                String title = node.path("title").asText("Untitled");
                ArrayNode tracksNode = (ArrayNode) node.path("tracks");
                List<TrackItem> trackItems = new ArrayList<>();
                if (tracksNode != null) {
                    for (JsonNode trackNode : tracksNode) {
                        String hash = trackNode.path("hash").asText(null);
                        if (hash == null || hash.isEmpty()) continue;
                        String instrument = trackNode.path("instrument").asText("Unknown Instrument");
                        String name = trackNode.path("name").asText(instrument);
                        int difficulty = trackNode.path("difficulty").asInt(-1);
                        List<Integer> tuning = new ArrayList<>();
                        JsonNode tuningNode = trackNode.path("tuning");
                        if (tuningNode.isArray()) {
                            tuningNode.forEach(t -> tuning.add(t.asInt()));
                        }
                        int trackIndex = trackItems.size();
                        trackItems.add(new TrackItem(songId, artist, title, trackIndex, name, instrument, hash, difficulty, tuning));
                    }
                }
                items.add(new SongItem(songId, artist, title, trackItems));
            }
            return items;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private static String buildPreview(TrackItem track) {
        return buildTabDisplay(track, track.prettyTabJson());
    }

    private static String buildTabDisplay(TrackItem track, String prettyJson) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildTrackMetadata(track));
        if (prettyJson != null && !prettyJson.isEmpty()) {
            sb.append("\n\nTab JSON:\n").append(prettyJson);
        }
        return sb.toString();
    }

    private static String buildTrackMetadata(TrackItem track) {
        StringBuilder sb = new StringBuilder();
        sb.append("Song: ").append(track.title()).append(" - ").append(track.artist()).append('\n');
        sb.append("Track: ").append(track.name()).append(" (").append(track.instrument()).append(")\n");
        if (track.partId() >= 0) {
            sb.append("Songsterr Part ID: ").append(track.partId()).append('\n');
        }
        sb.append("Search Index: ").append(track.index() + 1).append('\n');
        sb.append("Hash: ").append(track.hash()).append('\n');
        sb.append("Difficulty: ").append(track.difficulty() < 0 ? "N/A" : track.difficulty()).append('\n');
        sb.append("Tuning: ").append(formatTuning(track.tuning())).append('\n');
        sb.append("URL: ").append(buildTrackUrl(track)).append('\n');
        return sb.toString();
    }

    private void openTrackInBrowser(TrackItem track) {
        String url = buildTrackUrl(track);
        System.out.println("[Songsterr][open] " + url);
        if (!Desktop.isDesktopSupported()) {
            setStatus("Desktop browsing not supported. Open manually: " + url, true);
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            setStatus("Browse action not supported. URL: " + url, true);
            return;
        }
        try {
            desktop.browse(URI.create(url));
            setStatus("Opening tab in browser...", false);
        } catch (IOException ex) {
            setStatus("Failed to open browser: " + ex.getMessage(), true);
        }
    }

    private static String buildTrackUrl(TrackItem track) {
        String base = buildSongUrl(track.artist(), track.title(), track.songId());
        int trackIndex = track.index();
        if (trackIndex >= 0) {
            return base + "t" + trackIndex;
        }
        return base;
    }

    private static String prettifyJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ex) {
            return raw;
        }
    }

    private static String buildSongUrl(String artist, String title, int songId) {
        String artistSlug = slugify(artist);
        String titleSlug = slugify(title);
        return SONGSTERR_VIEW_BASE + artistSlug + "-" + titleSlug + "-tab-s" + songId;
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setStyle(error ? "-fx-text-fill: #b71c1c;" : "-fx-text-fill: #1b5e20;");
    }

    private static <T> javafx.util.Callback<ListView<T>, ListCell<T>> simpleCell(Function<T, String> function) {
        return listView -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : function.apply(item));
            }
        };
    }

    private static JsonNode extractStateJson(String html) throws JsonProcessingException {
        int marker = html.indexOf("<script id=\"state\"");
        if (marker == -1) {
            throw new JsonProcessingException("Unable to locate Songsterr state payload.") {};
        }
        int start = html.indexOf('>', marker);
        int end = html.indexOf("</script>", start);
        if (start == -1 || end == -1) {
            throw new JsonProcessingException("Malformed Songsterr state script.") {};
        }
        String jsonPayload = html.substring(start + 1, end);
        return MAPPER.readTree(jsonPayload);
    }

    private static String formatTuning(List<Integer> tuning) {
        if (tuning.isEmpty()) {
            return "Unknown";
        }
        return tuning.stream().map(TabPrototypeController::midiToNote).collect(Collectors.joining(" "));
    }

    private static String midiToNote(int midi) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int note = (midi % 12 + 12) % 12;
        int octave = (midi / 12) - 1;
        return names[note] + octave;
    }

    private static String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "song";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String slug = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-");
        if (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        if (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? "song" : slug;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException) {
            CompletionException completion = (CompletionException) throwable;
            if (completion.getCause() != null) {
                return completion.getCause();
            }
        }
        if (throwable instanceof ExecutionException) {
            ExecutionException execution = (ExecutionException) throwable;
            if (execution.getCause() != null) {
                return execution.getCause();
            }
        }
        return throwable;
    }

    private static class SongsterrThreadFactory implements ThreadFactory {
        private int idx = 0;

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "songsterr-worker-" + idx++);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static class SongDetails {
        private final int songId;
        private final int revisionId;
        private final Map<String, SongTrackInfo> tracksByHash;

        SongDetails(int songId, int revisionId, Map<String, SongTrackInfo> tracksByHash) {
            this.songId = songId;
            this.revisionId = revisionId;
            this.tracksByHash = tracksByHash;
        }

        SongTrackInfo trackForHash(String hash) {
            return tracksByHash.get(hash);
        }
    }

    private static class SongTrackInfo {
        private final int partId;
        private final int index;

        SongTrackInfo(int partId, int index) {
            this.partId = partId;
            this.index = index;
        }
    }

    private static class SongItem {
        private final int songId;
        private final String artist;
        private final String title;
        private final List<TrackItem> tracks;

        SongItem(int songId, String artist, String title, List<TrackItem> tracks) {
            this.songId = songId;
            this.artist = artist;
            this.title = title;
            this.tracks = tracks;
        }

        int songId() {
            return songId;
        }

        String artist() {
            return artist;
        }

        String title() {
            return title;
        }

        List<TrackItem> tracks() {
            return tracks;
        }

        String displayLabel() {
            return title + " — " + artist;
        }
    }

    private static class TrackItem {
        private final int songId;
        private final String artist;
        private final String title;
        private final int index;
        private final String name;
        private final String instrument;
        private final String hash;
        private final int difficulty;
        private final List<Integer> tuning;
        private int partId = -1;
        private String tabJson;
        private String prettyTabJson;

        TrackItem(int songId, String artist, String title, int index, String name, String instrument,
                  String hash, int difficulty, List<Integer> tuning) {
            this.songId = songId;
            this.artist = artist;
            this.title = title;
            this.index = index;
            this.name = name;
            this.instrument = instrument;
            this.hash = hash;
            this.difficulty = difficulty;
            this.tuning = tuning;
        }

        int songId() {
            return songId;
        }

        String artist() {
            return artist;
        }

        String title() {
            return title;
        }

        int index() {
            return index;
        }

        int partId() {
            return partId;
        }

        String name() {
            return name;
        }

        String instrument() {
            return instrument;
        }

        String hash() {
            return hash;
        }

        int difficulty() {
            return difficulty;
        }

        List<Integer> tuning() {
            return tuning;
        }

        String prettyTabJson() {
            return prettyTabJson;
        }

        void setSongMeta(int partId, int serverIndex) {
            if (partId >= 0) {
                this.partId = partId;
            }
        }

        void setTabJson(String raw, String pretty) {
            this.tabJson = raw;
            this.prettyTabJson = pretty;
        }

        String displayLabel() {
            String diff = difficulty >= 0 ? " (Difficulty " + difficulty + ")" : "";
            String part = partId >= 0 ? " (Part " + (partId + 1) + ")" : "";
            return name + " — " + instrument + diff + part;
        }
    }
}
