package org.openjfx;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.io.jvm.JVMAudioInputStream;
import be.tarsos.dsp.io.TarsosDSPAudioInputStream;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;

public class PrimaryController {

    @FXML private ComboBox<MixerItem> deviceBox;
    @FXML private Button startStopButton;
    @FXML private Button refreshButton;
    @FXML private Label statusLabel;
    @FXML private Label noteLabel;
    @FXML private Label freqLabel;
    @FXML private Label confLabel;

    private volatile AudioDispatcher dispatcher;
    private volatile Thread audioThread;

    private static final float SAMPLE_RATE = 44100f;
    private static final int BUFFER_SIZE = 2048; // Larger buffer improves low-frequency stability
    private static final int OVERLAP = 1024;

    @FXML
    private void initialize() {
        populateDevices();
        updateUIIdle();
    }

    @FXML
    private void onRefreshDevices() {
        populateDevices();
    }

    @FXML
    private void onStartStop() {
        if (dispatcher == null) {
            startDetection();
        } else {
            stopDetection();
        }
    }

    private void populateDevices() {
        List<MixerItem> mixers = listInputMixers();
        deviceBox.getItems().setAll(mixers);
        if (!mixers.isEmpty()) {
            deviceBox.getSelectionModel().select(0);
        }
    }

    private void startDetection() {
        MixerItem selected = deviceBox.getSelectionModel().getSelectedItem();
        Mixer mixer = selected != null ? AudioSystem.getMixer(selected.info) : null;

        try {
            if (mixer != null) {
                AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, true);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                line.open(format, BUFFER_SIZE);
                line.start();
                AudioInputStream ais = new AudioInputStream(line);
                TarsosDSPAudioInputStream tarsosIn = new JVMAudioInputStream(ais);
                dispatcher = new AudioDispatcher(tarsosIn, BUFFER_SIZE, OVERLAP);
            } else {
                dispatcher = AudioDispatcherFactory.fromDefaultMicrophone((int) SAMPLE_RATE, BUFFER_SIZE, OVERLAP);
            }
        } catch (Exception ex) {
            setStatus("Failed to open input: " + ex.getMessage());
            dispatcher = null;
            return;
        }

        PitchDetectionHandler handler = (PitchDetectionResult result, AudioEvent e) -> {
            final float pitch = result.getPitch();
            final float probability = result.getProbability();
            Platform.runLater(() -> updatePitch(pitch, probability));
        };

        AudioProcessor pitchProcessor = new PitchProcessor(
                PitchProcessor.PitchEstimationAlgorithm.YIN,
                SAMPLE_RATE,
                BUFFER_SIZE,
                handler
        );
        dispatcher.addAudioProcessor(pitchProcessor);

        audioThread = new Thread(dispatcher, "Audio Dispatcher");
        audioThread.setDaemon(true);
        audioThread.start();

        startStopButton.setText("Stop");
        setStatus("Listening...");
    }

    private void stopDetection() {
        AudioDispatcher d = dispatcher;
        dispatcher = null;
        if (d != null) {
            d.stop();
        }
        Thread t = audioThread;
        audioThread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        updateUIIdle();
    }

    private void updateUIIdle() {
        startStopButton.setText("Start");
        setStatus("Idle");
        noteLabel.setText("--");
        freqLabel.setText("Freq: -- Hz");
        confLabel.setText("Confidence: --");
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void updatePitch(float pitchHz, float confidence) {
        if (pitchHz <= 0) {
            noteLabel.setText("--");
            freqLabel.setText("Freq: -- Hz");
            confLabel.setText(String.format("Confidence: %.2f", confidence));
            return;
        }

        int midi = hzToMidi(pitchHz);
        String note = midiToNoteName(midi);
        int octave = (midi / 12) - 1;

        noteLabel.setText(note + octave);
        freqLabel.setText(String.format("Freq: %.2f Hz", pitchHz));
        confLabel.setText(String.format("Confidence: %.2f", confidence));
    }

    private static int hzToMidi(double hz) {
        // MIDI note calculation based on A4=440Hz
        double midi = 69 + 12 * (Math.log(hz / 440.0) / Math.log(2));
        return (int) Math.round(midi);
    }

    private static String midiToNoteName(int midi) {
        String[] names = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        int idx = Math.floorMod(midi, 12);
        return names[idx];
    }

    private static List<MixerItem> listInputMixers() {
        List<MixerItem> items = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            Line.Info targetLineInfo = new Line.Info(TargetDataLine.class);
            if (mixer.isLineSupported(targetLineInfo)) {
                items.add(new MixerItem(info));
            }
        }
        return items;
    }

    public static class MixerItem {
        final Mixer.Info info;
        MixerItem(Mixer.Info info) { this.info = info; }
        @Override public String toString() { return info.getName() + " - " + info.getDescription(); }
    }
}
