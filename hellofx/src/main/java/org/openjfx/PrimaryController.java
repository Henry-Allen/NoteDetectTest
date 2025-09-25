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
import be.tarsos.dsp.SpectralPeakProcessor;
import be.tarsos.dsp.ConstantQ;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

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
    @FXML private Label polyLabel;
    @FXML private Label chordLabel;
    @FXML private Label gestureLabel;
    @FXML private Label harmonicsLabel;
    @FXML private Canvas spectrumCanvas;
    @FXML private TextField a4Field;
    @FXML private Button calibrateButton;
    @FXML private Label tuningLabel;
    @FXML private javafx.scene.control.ComboBox<String> tunerModeBox;
    @FXML private Canvas tunerCanvas;
    @FXML private Label tunerTargetLabel;
    @FXML private Label tunerCentsLabel;

    private volatile AudioDispatcher dispatcher;
    private volatile Thread audioThread;
    private SpectralPeakProcessor spectralPeaks;
    private ConstantQ constantQ;
    private int cqtFftLen;
    private final java.util.Deque<Double> mainPitchHistory = new java.util.ArrayDeque<>(64);
    private final java.util.Deque<double[]> chromaHistory = new java.util.ArrayDeque<>(16);
    private float[] lastMagnitudes;
    private java.util.List<SpectralPeakProcessor.SpectralPeak> lastPeaks;
    private volatile boolean calibrating = false;
    private final java.util.List<Double> calibrationSamples = new java.util.ArrayList<>();
    private volatile double a4RefHz = 440.0;
    private volatile String tunerMode = "Auto";

    private static final float SAMPLE_RATE = 44100f;
    private static final int BUFFER_SIZE = 2048; // Larger buffer improves low-frequency stability
    private static final int OVERLAP = 1024;

    @FXML
    private void initialize() {
        populateDevices();
        updateUIIdle();
        if (a4Field != null) a4Field.setText(String.format("%.1f", a4RefHz));
        updateTuningLabel();
        setupTunerUI();
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

        spectralPeaks = new SpectralPeakProcessor(BUFFER_SIZE, OVERLAP, (int) SAMPLE_RATE);
        dispatcher.addAudioProcessor(spectralPeaks);
        // Initialize Constant-Q for robust chord/chroma detection
        // Cover guitar range and a few harmonics: ~55Hz (A1) to 3520Hz (A7)
        constantQ = new ConstantQ(SAMPLE_RATE, 55f, 3520f, 36f);
        cqtFftLen = constantQ.getFFTlength();

        dispatcher.addAudioProcessor(new AudioProcessor() {
            @Override
            public boolean process(AudioEvent audioEvent) {
                analyzeSpectrum(audioEvent.getFloatBuffer());
                return true;
            }
            @Override
            public void processingFinished() { }
        });

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
        polyLabel.setText("--");
        chordLabel.setText("--");
        gestureLabel.setText("--");
        harmonicsLabel.setText("--");
        if (a4Field != null) a4Field.setText(String.format("%.1f", a4RefHz));
        updateTuningLabel();
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

        maybeCollectCalibration(pitchHz, confidence);

        int midi = hzToMidiRef(pitchHz);
        String note = midiToNoteName(midi);
        int octave = (midi / 12) - 1;

        noteLabel.setText(note + octave);
        freqLabel.setText(String.format("Freq: %.2f Hz", pitchHz));
        confLabel.setText(String.format("Confidence: %.2f", confidence));
        updateTuner(pitchHz, confidence);
    }

    private int hzToMidiRef(double hz) {
        // MIDI note calculation based on current A4 reference
        double midi = 69 + 12 * (Math.log(hz / a4RefHz) / Math.log(2));
        return (int) Math.round(midi);
    }

    private double midiToFreqRef(int midi) {
        return a4RefHz * Math.pow(2.0, (midi - 69) / 12.0);
    }

    private static String midiToNoteName(int midi) {
        String[] names = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        int idx = Math.floorMod(midi, 12);
        return names[idx];
    }

    private void analyzeSpectrum(float[] frame) {
        if (spectralPeaks == null) return;
        float[] mags = spectralPeaks.getMagnitudes();
        float[] freqs = spectralPeaks.getFrequencyEstimates();
        // Parameters for peak picking
        int medianLen = 31;
        float noiseFactor = 1.2f;
        int numberOfPeaks = 8;
        int minDistanceCents = 60; // separate at least ~semitone
        float[] noise = SpectralPeakProcessor.calculateNoiseFloor(mags, medianLen, noiseFactor);
        java.util.List<Integer> localMax = SpectralPeakProcessor.findLocalMaxima(mags, noise);
        java.util.List<SpectralPeakProcessor.SpectralPeak> peaks = SpectralPeakProcessor.findPeaks(mags, freqs, localMax, numberOfPeaks, minDistanceCents);
        lastMagnitudes = mags.clone();
        lastPeaks = new java.util.ArrayList<>(peaks);

        // Compute Constant-Q chroma for robust chord detection
        double[] chroma = computeChroma(frame);
        // Smooth chroma over recent frames for stability
        pushChroma(chroma, 8);
        double[] avgChroma = averageChroma();
        java.util.List<Integer> topPcs = topPitchClasses(avgChroma, 6);
        java.util.List<NotePeak> notes = new java.util.ArrayList<>();
        for (int pc : topPcs) {
            // Estimate an octave for labeling using the strongest peak near that pc
            double f = findPeakNearPitchClass(peaks, pc);
            int midi = hzToMidiRef(f > 0 ? f : a4RefHz);
            String name = midiToNoteName(midi) + ((midi / 12) - 1);
            notes.add(new NotePeak(name, midi, f, 0));
        }

        // Chord guess from chroma template matching
        String chord = guessChordFromChroma(avgChroma);

        // Gesture estimation on main note history
        if (!notes.isEmpty()) {
            double mainHz = notes.get(0).hz;
            updatePitchHistory(mainHz);
        }
        String gesture = estimateGesture();

        // Harmonics presence relative to dominant note
        String harmonics = estimateHarmonics(notes);

        // Update UI
        Platform.runLater(() -> {
            drawSpectrum(lastMagnitudes, lastPeaks);
            if (notes.isEmpty()) {
                polyLabel.setText("--");
                chordLabel.setText("--");
                gestureLabel.setText("--");
                harmonicsLabel.setText("--");
            } else {
                String list = notes.stream().map(n -> n.name).reduce((a,b) -> a + ", " + b).orElse("--");
                polyLabel.setText(list);
                chordLabel.setText(chord);
                gestureLabel.setText(gesture);
                harmonicsLabel.setText(harmonics);
            }
        });
    }

    private void pushChroma(double[] chroma, int max) {
        chromaHistory.addLast(chroma.clone());
        while (chromaHistory.size() > max) chromaHistory.pollFirst();
    }

    private double[] averageChroma() {
        double[] out = new double[12];
        if (chromaHistory.isEmpty()) return out;
        for (double[] c : chromaHistory) {
            for (int i = 0; i < 12; i++) out[i] += c[i];
        }
        for (int i = 0; i < 12; i++) out[i] /= chromaHistory.size();
        // Normalize to [0,1]
        double max = 0; for (double v : out) max = Math.max(max, v);
        if (max > 0) for (int i = 0; i < 12; i++) out[i] /= max;
        return out;
    }

    private double[] computeChroma(float[] frame) {
        if (constantQ == null || frame == null) return new double[12];
        int len = Math.max(cqtFftLen, frame.length);
        float[] buf = new float[cqtFftLen];
        // Copy latest frame; if shorter, zero-pad
        int copy = Math.min(frame.length, cqtFftLen);
        System.arraycopy(frame, 0, buf, 0, copy);
        constantQ.calculateMagintudes(buf);
        float[] mags = constantQ.getMagnitudes();
        float[] freqs = constantQ.getFreqencies();
        double[] chroma = new double[12];
        for (int i = 0; i < mags.length; i++) {
            float f = freqs[i];
            if (f < 55 || f > 4000) continue;
            int midi = hzToMidiRef(f);
            int pc = Math.floorMod(midi, 12);
            // Log compression for robustness
            double w = Math.log1p(mags[i]);
            // De-emphasize low freqs a bit to reduce bass dominance
            double lf = 1.0 / Math.sqrt(Math.max(1.0, f / 110.0));
            chroma[pc] += w * lf;
        }
        // Normalize
        double max = 0;
        for (double v : chroma) max = Math.max(max, v);
        if (max > 0) for (int i = 0; i < 12; i++) chroma[i] /= max;
        return chroma;
    }

    private java.util.List<Integer> topPitchClasses(double[] chroma, int k) {
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) idx.add(i);
        idx.sort((a,b) -> Double.compare(chroma[b], chroma[a]));
        if (k < idx.size()) return new java.util.ArrayList<>(idx.subList(0, k));
        return idx;
    }

    private double findPeakNearPitchClass(java.util.List<SpectralPeakProcessor.SpectralPeak> peaks, int pc) {
        double bestHz = -1; double bestMag = -1e9;
        if (peaks == null) return -1;
        for (SpectralPeakProcessor.SpectralPeak p : peaks) {
            int midi = hzToMidiRef(p.getFrequencyInHertz());
            if (Math.floorMod(midi, 12) == pc) {
                if (p.getMagnitude() > bestMag) { bestMag = p.getMagnitude(); bestHz = p.getFrequencyInHertz(); }
            }
        }
        return bestHz;
    }

    @FXML
    private void onSetA4() {
        if (a4Field == null) return;
        try {
            double val = Double.parseDouble(a4Field.getText().trim());
            if (val < 400 || val > 500) throw new IllegalArgumentException();
            a4RefHz = val;
            updateTuningLabel();
        } catch (Exception ex) {
            setStatus("Invalid A4 value (400–500 Hz)");
        }
    }

    @FXML
    private void onCalibrate() {
        if (dispatcher == null) {
            setStatus("Start audio, then calibrate");
            return;
        }
        if (calibrating) return;
        calibrating = true;
        calibrationSamples.clear();
        calibrateButton.setDisable(true);
        setStatus("Calibrating... Pluck a string (2s)");
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            double offset = computeCalibrationOffset();
            if (!Double.isNaN(offset)) {
                // Adjust A4 reference by the measured offset (in cents)
                a4RefHz = a4RefHz * Math.pow(2.0, offset / 1200.0);
            }
            calibrating = false;
            Platform.runLater(() -> {
                calibrateButton.setDisable(false);
                updateTuningLabel();
                setStatus("Calibration done");
                if (a4Field != null) a4Field.setText(String.format("%.1f", a4RefHz));
            });
        }, "Calibrate").start();
    }

    private void maybeCollectCalibration(double pitchHz, float confidence) {
        if (!calibrating) return;
        if (confidence < 0.85f) return;
        int midi = hzToMidiRef(pitchHz);
        double targetHz = midiToFreqRef(midi);
        if (targetHz <= 0) return;
        double cents = 1200.0 * Math.log(pitchHz / targetHz) / Math.log(2);
        synchronized (calibrationSamples) { calibrationSamples.add(cents); }
    }

    private double computeCalibrationOffset() {
        double[] arr;
        synchronized (calibrationSamples) {
            if (calibrationSamples.isEmpty()) return Double.NaN;
            arr = calibrationSamples.stream().mapToDouble(Double::doubleValue).toArray();
        }
        java.util.Arrays.sort(arr);
        double median = arr[arr.length/2];
        // Clamp to a reasonable range (e.g., +-50 cents)
        if (median > 50) median = 50; if (median < -50) median = -50;
        return median;
    }

    private void updateTuningLabel() {
        if (tuningLabel == null) return;
        double offset = 1200.0 * Math.log(a4RefHz / 440.0) / Math.log(2);
        tuningLabel.setText(String.format("A4: %.1f Hz (%+.1fc)", a4RefHz, offset));
    }

    private String guessChordFromChroma(double[] chroma) {
        String[] pcNames = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        double total = 0; for (double v : chroma) total += v;
        if (total <= 1e-6) return "--";

        double bestScore = -1e9; String bestLabel = "--";

        // thresholds and weights
        double thr3Rel = 0.35;   // require at least 35% of max(root,fifth) on the third to call maj/min
        double thr7Rel = 0.35;   // require at least 35% on the seventh to call 7/m7
        double lambdaComplex = 0.10; // complexity penalty per extra interval beyond root+fifth

        for (int root = 0; root < 12; root++) {
            double R = chroma[root];
            double E5 = chroma[(root + 7) % 12];
            double EM3 = chroma[(root + 4) % 12];
            double Em3 = chroma[(root + 3) % 12];
            double E7 = chroma[(root + 10) % 12];

            double base = Math.max(1e-6, Math.max(R, E5));
            boolean hasMaj3 = EM3 >= thr3Rel * base;
            boolean hasMin3 = Em3 >= thr3Rel * base;
            boolean has7 = E7 >= thr7Rel * base;

            // POWER (5)
            double powerExplained = R + E5;
            double powerPurity = powerExplained / total;
            double powerScore = (1.00 * R + 0.95 * E5) * (0.6 + 0.4 * powerPurity);
            // small penalty if strong third present (not a true power chord)
            double thirdLeak = Math.max(EM3, Em3);
            powerScore -= 0.05 * Math.max(0, thirdLeak - 0.25 * base);
            String powerLabel = pcNames[root] + " 5";

            // MAJOR
            double majorScore = -1e9; String majorLabel = pcNames[root] + " Maj";
            if (hasMaj3) {
                double explained = R + E5 + EM3;
                double purity = explained / total;
                majorScore = (1.0 * R + 0.85 * EM3 + 0.90 * E5) * (0.6 + 0.4 * purity)
                        - lambdaComplex * (3 - 2);
            }

            // MINOR
            double minorScore = -1e9; String minorLabel = pcNames[root] + " Min";
            if (hasMin3) {
                double explained = R + E5 + Em3;
                double purity = explained / total;
                minorScore = (1.0 * R + 0.85 * Em3 + 0.90 * E5) * (0.6 + 0.4 * purity)
                        - lambdaComplex * (3 - 2);
            }

            // DOM7
            double dom7Score = -1e9; String dom7Label = pcNames[root] + " 7";
            if (hasMaj3 && has7) {
                double explained = R + E5 + EM3 + E7;
                double purity = explained / total;
                dom7Score = (1.0 * R + 0.80 * EM3 + 0.90 * E5 + 0.60 * E7) * (0.6 + 0.4 * purity)
                        - lambdaComplex * (4 - 2);
            }

            // MIN7
            double min7Score = -1e9; String min7Label = pcNames[root] + " m7";
            if (hasMin3 && has7) {
                double explained = R + E5 + Em3 + E7;
                double purity = explained / total;
                min7Score = (1.0 * R + 0.80 * Em3 + 0.90 * E5 + 0.60 * E7) * (0.6 + 0.4 * purity)
                        - lambdaComplex * (4 - 2);
            }

            // Choose best for this root with a bias toward simpler models when close in score
            double[] scores = new double[]{powerScore, majorScore, minorScore, dom7Score, min7Score};
            String[] labels = new String[]{powerLabel, majorLabel, minorLabel, dom7Label, min7Label};

            // Find top two
            int bestIdx = 0; double bestLocal = scores[0];
            for (int i = 1; i < scores.length; i++) if (scores[i] > bestLocal) { bestLocal = scores[i]; bestIdx = i; }
            // If best is a 7th but only marginally better than power, prefer power
            if ((bestIdx == 3 || bestIdx == 4) && powerScore > -1e8 && (bestLocal - powerScore) < 0.12) {
                bestIdx = 0; bestLocal = powerScore;
            }

            if (bestLocal > bestScore) { bestScore = bestLocal; bestLabel = labels[bestIdx]; }
        }

        if (bestScore < 0.20) return "Uncertain";
        return bestLabel;
    }

    private void drawSpectrum(float[] magnitudes, java.util.List<SpectralPeakProcessor.SpectralPeak> peaks) {
        if (spectrumCanvas == null || magnitudes == null) return;
        double w = spectrumCanvas.getWidth();
        double h = spectrumCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        // Normalize magnitudes per frame
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (float v : magnitudes) { if (v < min) min = v; if (v > max) max = v; }
        double range = Math.max(1e-3, (max - min));

        GraphicsContext g = spectrumCanvas.getGraphicsContext2D();
        g.setFill(Color.web("#1e1e1e"));
        g.fillRect(0, 0, w, h);

        // Grid lines
        g.setStroke(Color.web("#303030"));
        g.setLineWidth(1);
        for (int i = 1; i <= 4; i++) {
            double y = i * (h / 5.0);
            g.strokeLine(0, y, w, y);
        }

        // Spectrum polyline
        g.setStroke(Color.web("#74c0fc"));
        g.setLineWidth(1.0);
        int n = magnitudes.length;
        double xstep = w / Math.max(1, (n - 1));
        double x = 0;
        double y0 = h - ((magnitudes[0] - min) / range) * h;
        g.beginPath();
        g.moveTo(0, y0);
        for (int i = 1; i < n; i++) {
            x = i * xstep;
            double y = h - ((magnitudes[i] - min) / range) * h;
            g.lineTo(x, y);
        }
        g.stroke();

        // Draw peaks as circles
        if (peaks != null) {
            g.setFill(Color.web("#ffb86c"));
            for (SpectralPeakProcessor.SpectralPeak p : peaks) {
                int bin = p.getBin();
                if (bin < 0 || bin >= n) continue;
                double px = bin * xstep;
                double py = h - ((magnitudes[bin] - min) / range) * h;
                double r = 4.0;
                g.fillOval(px - r, py - r, r * 2, r * 2);
            }
        }
    }

    private static class StringSpec {
        final String name; final int midi;
        StringSpec(String name, int midi){ this.name=name; this.midi=midi; }
        double freq(double a4) { return a4 * Math.pow(2.0, (midi - 69) / 12.0); }
    }

    private static final StringSpec[] STANDARD_STRINGS = new StringSpec[]{
            new StringSpec("E2", 40),
            new StringSpec("A2", 45),
            new StringSpec("D3", 50),
            new StringSpec("G3", 55),
            new StringSpec("B3", 59),
            new StringSpec("E4", 64)
    };

    private void setupTunerUI() {
        if (tunerModeBox == null) return;
        tunerModeBox.getItems().setAll("Auto", "E2", "A2", "D3", "G3", "B3", "E4");
        tunerModeBox.getSelectionModel().select("Auto");
        tunerModeBox.valueProperty().addListener((obs, o, n) -> tunerMode = n);
        tunerTargetLabel.setText("--");
        tunerCentsLabel.setText("--");
        drawTuner(Double.NaN, 0);
    }

    private void updateTuner(double pitchHz, float confidence) {
        if (tunerCanvas == null) return;
        if (pitchHz <= 0 || confidence < 0.75f) {
            tunerTargetLabel.setText("--");
            tunerCentsLabel.setText("--");
            drawTuner(Double.NaN, 0);
            return;
        }

        // Determine target string
        StringSpec target = null;
        if ("Auto".equals(tunerMode)) {
            double bestAbsCents = Double.MAX_VALUE;
            for (StringSpec s : STANDARD_STRINGS) {
                double cents = 1200.0 * Math.log(pitchHz / s.freq(a4RefHz)) / Math.log(2);
                double ac = Math.abs(cents);
                if (ac < bestAbsCents) { bestAbsCents = ac; target = s; }
            }
            // If way off (> 500c), don't snap
            if (bestAbsCents > 500) target = null;
        } else {
            for (StringSpec s : STANDARD_STRINGS) if (s.name.equals(tunerMode)) { target = s; break; }
        }

        if (target == null) {
            tunerTargetLabel.setText("--");
            tunerCentsLabel.setText("--");
            drawTuner(Double.NaN, 0);
            return;
        }

        double targetHz = target.freq(a4RefHz);
        double cents = 1200.0 * Math.log(pitchHz / targetHz) / Math.log(2);
        tunerTargetLabel.setText(target.name);
        tunerCentsLabel.setText(String.format("%+.1fc", cents));
        drawTuner(cents, confidence);
    }

    private void drawTuner(double cents, float confidence) {
        GraphicsContext g = tunerCanvas.getGraphicsContext2D();
        double w = tunerCanvas.getWidth();
        double h = tunerCanvas.getHeight();
        // background
        g.setFill(Color.web("#1e1e1e"));
        g.fillRect(0, 0, w, h);
        // center line and ticks
        double mid = w / 2.0;
        g.setStroke(Color.web("#404040"));
        g.setLineWidth(1);
        g.strokeLine(mid, 0, mid, h);
        for (int c = -50; c <= 50; c += 25) {
            double x = mapCentsToX(c, w);
            g.strokeLine(x, h*0.6, x, h);
            g.setFill(Color.web("#808080"));
            g.fillText(Integer.toString(c), x-8, h*0.55);
        }

        if (Double.isNaN(cents)) {
            g.setFill(Color.web("#888"));
            g.fillText("listening...", mid - 30, h*0.35);
            return;
        }

        // Needle color based on error
        double ac = Math.abs(cents);
        Color color = ac <= 5 ? Color.web("#69db7c") : ac <= 15 ? Color.web("#ffd43b") : Color.web("#ff6b6b");

        double x = mapCentsToX(cents, w);
        g.setStroke(color);
        g.setLineWidth(3);
        g.strokeLine(x, 0, x, h*0.6);

        // Center band for in-tune
        g.setFill(Color.web("#2b8a3e", 0.25));
        double xL = mapCentsToX(-5, w);
        double xR = mapCentsToX(5, w);
        g.fillRect(xL, 0, xR-xL, h*0.6);
    }

    private double mapCentsToX(double cents, double width) {
        // Map -50..+50 cents to 10..(width-10)
        double clamped = Math.max(-50, Math.min(50, cents));
        double t = (clamped + 50) / 100.0;
        return 10 + t * (width - 20);
    }

    private void updatePitchHistory(double hz) {
        if (hz <= 0) return;
        if (mainPitchHistory.size() >= 50) mainPitchHistory.pollFirst();
        mainPitchHistory.addLast(hz);
    }

    private String estimateGesture() {
        if (mainPitchHistory.size() < 6) return "--";
        double[] arr = mainPitchHistory.stream().mapToDouble(Double::doubleValue).toArray();
        // Convert to cents relative to first value
        double base = arr[0];
        if (base <= 0) return "--";
        double[] cents = new double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            cents[i] = 1200.0 * Math.log(arr[i] / base) / Math.log(2);
        }
        double total = cents[cents.length - 1] - cents[0];
        double slope = total / cents.length;
        double var = 0.0;
        for (double c : cents) var += (c - total * c / total) * (c - total * c / total);
        double absDiffSum = 0.0;
        int signChanges = 0;
        double prevDiff = 0.0;
        for (int i = 1; i < cents.length; i++) {
            double diff = cents[i] - cents[i-1];
            absDiffSum += Math.abs(diff);
            if (i > 1 && Math.signum(diff) != Math.signum(prevDiff)) signChanges++;
            prevDiff = diff;
        }
        double range = Math.abs(total);
        double avgStep = absDiffSum / (cents.length - 1);
        if (range > 150 && signChanges < 2) return total > 0 ? "Slide Up" : "Slide Down";
        if (range > 25 && range <= 150 && signChanges < 3) return total > 0 ? "Bend Up" : "Bend Down";
        if (signChanges > 6 && avgStep < 30) return "Vibrato";
        return "Stable";
    }

    private String estimateHarmonics(java.util.List<NotePeak> notes) {
        if (notes == null || notes.isEmpty()) return "--";
        double f0 = notes.get(0).hz;
        int count = 0;
        for (NotePeak n : notes) {
            if (n == notes.get(0)) continue;
            double ratio = n.hz / f0;
            double nearest = Math.round(ratio);
            if (nearest >= 2 && Math.abs(ratio - nearest) < 0.05) count++;
        }
        return count >= 2 ? ("Yes (" + count + ")") : "No";
    }

    private String guessChord(java.util.List<NotePeak> notes) {
        if (notes.size() < 2) return "--";
        java.util.Set<Integer> pcs = new java.util.HashSet<>();
        for (NotePeak n : notes) pcs.add(n.midi % 12);
        String[] names = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        for (int root = 0; root < 12; root++) {
            boolean major = pcs.contains(root) && pcs.contains((root+4)%12) && pcs.contains((root+7)%12);
            boolean minor = pcs.contains(root) && pcs.contains((root+3)%12) && pcs.contains((root+7)%12);
            if (major) return names[root] + " Maj";
            if (minor) return names[root] + " Min";
        }
        return "Uncertain";
    }

    private static class NotePeak {
        final String name; final int midi; final double hz; final double mag;
        NotePeak(String name, int midi, double hz, double mag){ this.name=name; this.midi=midi; this.hz=hz; this.mag=mag; }
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
