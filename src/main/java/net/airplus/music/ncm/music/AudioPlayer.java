// skid SetsunaClient
package net.airplus.music.ncm.music;

import net.airplus.music.ncm.audio.processing.sound.FFT;
import net.airplus.music.ncm.audio.processing.sound.JSynFFT;
import net.airplus.music.ncm.audio.processing.sound.SoundFile;
import net.airplus.music.ncm.util.SpectrumVisualizer;

import java.io.File;

/**
 * 播放视频里面的音频
 */
public class AudioPlayer {
    public SoundFile player;
    public Runnable afterPlayed;

    public float volume = 0.25f;

    public AudioPlayer(File file) {
        finished = false;

        this.player = new SoundFile(file.getAbsolutePath());
        this.setListeners();
    }

    public void setAudio(File file) {
        float previousVolume = this.volume;
        this.close();

        this.player = new SoundFile(file.getAbsolutePath());
        this.setListeners();
        this.volume = previousVolume;
        finished = false;
    }

    FFT fft = new FFT(128, callback);

    public FFT getFft() {
        return fft;
    }

    public static volatile float[] bandValues = new float[1];
    public static final SpectrumVisualizer visualizer = new SpectrumVisualizer(48000, JSynFFT.FFT_SIZE, 1024);


    static int skipCount = 0;

    public static final JSynFFT.FFTCalcCallback callback = fft -> {
        int skipAmount = 4;

        if (skipCount < skipAmount) {
            skipCount++;
        } else {
            skipCount = 0;
            bandValues = visualizer.processFFT(fft);
        }
    };

    public void setListeners() {
        fft.removeInput();
        fft.input(this.player);

        player.setOnFinished(() -> finished = true);
    }

    public void play() {
        finished = false;
        this.player.play();
        this.player.amp(volume);
    }

    public void setPlaybackTime(float millis) {
        try {
            boolean wasPlaying = this.player.isPlaying();
            this.player.jump(millis / 1000F);
            this.player.amp(volume);
            if (wasPlaying && !this.player.isPlaying()) {
                this.player.play();
                this.player.amp(volume);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        try {
            this.player.jump(0);
            player.stop();
            player.cleanUp();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void stopForSwitch() {
        try {
            finished = true;
            this.player.jump(0);
            player.stop();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean finished;

    public boolean isFinished() {
        return finished;
    }

    public void setAfterPlayed(Runnable runnable) {
        this.afterPlayed = runnable;
        this.player.setOnFinished(() -> {
            finished = true;
            runnable.run();
        });
    }

    public float getTotalTimeSeconds() {
        return (int) this.player.duration();
    }

    public float getCurrentTimeSeconds() {
        return (int) (getCurrentTimeMillis() / 1000);
    }

    public float getTotalTimeMillis() {
        return getTotalTimeSeconds() * 1000;
    }

    public float getCurrentTimeMillis() {
        return this.player.position() * 1000;
    }

    public boolean isPausing() {
        return !this.player.isPlaying();
    }

    public void setVolume(float volume) {
        this.volume = volume;
        this.player.amp(this.getVolume());
    }

    public float getVolume() {
        return volume;
    }

    public void pause() {
        this.player.pause();
    }

    public void unpause() {
        finished = false;
        this.player.play();
        this.player.amp(volume);
    }
}
