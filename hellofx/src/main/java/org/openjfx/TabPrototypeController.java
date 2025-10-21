package org.openjfx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TabPrototypeController {

    private static final String SONGSTERR_SEARCH_ENDPOINT = "https://www.songsterr.com/api/songs?pattern=";
    private static final String SONGSTERR_SONG_ENDPOINT = "https://www.songsterr.com/a/wsa/song?id=";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, new SongsterrThreadFactory());
    private static final Path TAB_DIRECTORY = Path.of("tab");

    private final ObservableList<SongItem> songs = FXCollections.observableArrayList();
    private final ObservableList<TrackItem> tracks = FXCollections.observableArrayList();

    @FXML private TextField searchField;
    @FXML private Button searchButton;
    @FXML private ListView<SongItem> songsList;
    @FXML private ListView<TrackItem> tracksList;
    @FXML private Button downloadButton;
    @FXML private TextArea tabDisplay;
    @FXML private Label statusLabel;

    @FXML
    private void initialize() {
        songsList.setItems(songs);
        songsList.setCellFactory(simpleCell(SongItem::displayLabel));
        songsList.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            tracks.clear();
            tabDisplay.clear();
            downloadButton.setDisable(true);
            if (newItem != null) {
                tracks.addAll(newItem.tracks());
                setStatus("Select a track to inspect or download.", false);
            }
        });

        tracksList.setItems(tracks);
        tracksList.setCellFactory(simpleCell(TrackItem::displayLabel));
        tracksList.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            downloadButton.setDisable(newItem == null);
            if (newItem != null) {
                tabDisplay.setText(buildPreview(newItem));
            } else {
                tabDisplay.clear();
            }
        });

        searchField.setOnAction(event -> onSearch());
        downloadButton.setDisable(true);
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
        downloadButton.setDisable(true);
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
                    setStatus("Found " + results.size() + " song(s). Select one to view tracks.", false);
                }));
    }

    @FXML
    private void onDownload() {
        SongItem song = songsList.getSelectionModel().getSelectedItem();
        TrackItem track = tracksList.getSelectionModel().getSelectedItem();
        if (song == null || track == null) {
            return;
        }

        downloadButton.setDisable(true);
        setStatus("Downloading \"" + track.name() + "\"...", false);

        CompletableFuture
                .supplyAsync(() -> downloadTrack(song, track), EXECUTOR)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    downloadButton.setDisable(false);
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        setStatus("Download failed: " + cause.getMessage(), true);
                        return;
                    }
                    if (result == null) {
                        setStatus("No data returned for the selected track.", true);
                        return;
                    }
                    track.setDownloadedPreview(result.preview());
                    track.setDownloadedFile(result.file());
                    tabDisplay.setText(result.preview());
                    setStatus("Saved tab metadata to " + result.file().toString(), false);
                }));
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
            if (response.statusCode() != 200) {
                throw new IOException("Songsterr returned HTTP " + response.statusCode());
            }

            JsonNode root = MAPPER.readTree(response.body());
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
                        trackItems.add(new TrackItem(songId, artist, title, name, instrument, hash, difficulty, tuning));
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

    private DownloadResult downloadTrack(SongItem song, TrackItem track) {
        try {
            Files.createDirectories(TAB_DIRECTORY);
            String html = fetchSongHtml(song.songId());
            JsonNode state = extractStateJson(html);

            JsonNode meta = state.path("meta").path("current");
            int revisionId = meta.path("revisionId").asInt(-1);

            JsonNode matchingTrack = findTrackNode(meta.path("tracks"), track.hash());
            if (matchingTrack == null) {
                throw new IOException("Track not present in Songsterr metadata.");
            }

            ObjectNode export = MAPPER.createObjectNode();
            export.put("downloadedAt", LocalDateTime.now().toString());
            export.put("songId", song.songId());
            export.put("revisionId", revisionId);
            export.set("song", meta);
            export.set("track", matchingTrack);
            export.set("part", state.path("part"));

            String prettyJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(export);
            Path file = TAB_DIRECTORY.resolve(buildFilename(song, track));
            Files.writeString(file, prettyJson, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            String preview = buildDownloadedPreview(track, matchingTrack, state.path("part"));
            return new DownloadResult(file, preview);
        } catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private static String fetchSongHtml(int songId) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(SONGSTERR_SONG_ENDPOINT + songId))
                    .header("User-Agent", "NoteDetectTest/1.0 (+https://github.com)")
                    .header("Accept", "text/html")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Songsterr view endpoint returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading Songsterr page.", ex);
        }
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

    private static JsonNode findTrackNode(JsonNode tracksNode, String hash) {
        if (tracksNode == null || !tracksNode.isArray()) return null;
        for (JsonNode node : tracksNode) {
            if (Objects.equals(node.path("hash").asText(), hash)) {
                return node;
            }
        }
        return null;
    }

    private static String buildDownloadedPreview(TrackItem track, JsonNode trackNode, JsonNode partNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Song: ").append(track.title()).append(" - ").append(track.artist()).append('\n');
        sb.append("Track: ").append(track.name()).append(" (").append(track.instrument()).append(")\n");
        sb.append("Hash: ").append(track.hash()).append('\n');
        sb.append("Difficulty: ").append(trackNode.path("difficulty").isMissingNode() ? "N/A" : trackNode.path("difficulty").asInt()).append('\n');
        sb.append("Views: ").append(trackNode.path("views").isMissingNode() ? "N/A" : trackNode.path("views").asInt()).append('\n');
        sb.append("Tuning: ").append(formatTuning(track.tuning())).append('\n');
        sb.append('\n');

        JsonNode linesNode = partNode.path("lines").path("lines");
        if (linesNode.isArray() && linesNode.size() > 0) {
            sb.append("Tab Lines Preview (first line):\n");
            JsonNode firstLine = linesNode.get(0);
            JsonNode bars = firstLine.path("bars");
            for (int i = 0; i < Math.min(4, bars.size()); i++) {
                sb.append(bars.get(i).toString()).append('\n');
            }
            if (bars.size() > 4) {
                sb.append("... ").append(bars.size() - 4).append(" more bars\n");
            }
        } else {
            sb.append("Tab notation not contained in this response. Songsterr loads detailed tab lines lazily.\n");
            sb.append("Metadata saved for offline experimentation.\n");
        }

        return sb.toString();
    }

    private static String buildPreview(TrackItem track) {
        StringBuilder sb = new StringBuilder();
        sb.append("Song: ").append(track.title()).append(" - ").append(track.artist()).append('\n');
        sb.append("Track: ").append(track.name()).append(" (").append(track.instrument()).append(")\n");
        sb.append("Hash: ").append(track.hash()).append('\n');
        sb.append("Difficulty: ").append(track.difficulty() < 0 ? "N/A" : track.difficulty()).append('\n');
        sb.append("Tuning: ").append(formatTuning(track.tuning())).append('\n');
        track.downloadedFile().ifPresent(file -> sb.append("\nSaved file: ").append(file).append('\n'));
        track.downloadedPreview().ifPresent(preview -> sb.append("\nPreview:\n").append(preview));
        return sb.toString();
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

    private static String buildFilename(SongItem song, TrackItem track) {
        String safeTitle = sanitize(song.title());
        String safeTrack = sanitize(track.name());
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        return safeTitle + "_" + safeTrack + "_" + timestamp + ".json";
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9-_ ]", "").trim();
        if (sanitized.isEmpty()) {
            return "song";
        }
        return sanitized.replace(' ', '_');
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
        private final String name;
        private final String instrument;
        private final String hash;
        private final int difficulty;
        private final List<Integer> tuning;
        private Path downloadedFile;
        private String downloadedPreview;

        TrackItem(int songId, String artist, String title, String name, String instrument,
                  String hash, int difficulty, List<Integer> tuning) {
            this.songId = songId;
            this.artist = artist;
            this.title = title;
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

        String displayLabel() {
            String diff = difficulty >= 0 ? " (Difficulty " + difficulty + ")" : "";
            return name + " — " + instrument + diff;
        }

        Optional<Path> downloadedFile() {
            return Optional.ofNullable(downloadedFile);
        }

        Optional<String> downloadedPreview() {
            return Optional.ofNullable(downloadedPreview);
        }

        void setDownloadedFile(Path file) {
            this.downloadedFile = file;
        }

        void setDownloadedPreview(String preview) {
            this.downloadedPreview = preview;
        }
    }

    private static class DownloadResult {
        private final Path file;
        private final String preview;

        DownloadResult(Path file, String preview) {
            this.file = file;
            this.preview = preview;
        }

        Path file() {
            return file;
        }

        String preview() {
            return preview;
        }
    }

}
