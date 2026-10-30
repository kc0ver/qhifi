package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.AudioOutputDevice;
import dev.t1m3.qplayer.audio.AudioOutputStatus;
import dev.t1m3.qplayer.audio.HifiCapabilities;
import dev.t1m3.qplayer.audio.HifiOutputControl;
import dev.t1m3.qplayer.audio.HifiOutputMode;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The controller's half of the HiFi chain readout: it must report "unsupported"
 *  without blowing up when the host's backend has no {@link HifiOutputControl},
 *  and mirror the backend's snapshot when it does. */
public class PlayerControllerHifiTest {

    private static final long SETTLE_TIMEOUT_MS = 5000L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void backendWithoutHifiControlReportsUnsupported() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("hifi-plain").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(new FakeAudioBackend(), track -> { },
                    NeteaseClient.INSTANCE);
            final PlayerController underTest = controller;

            controller.refreshHifiStatus();
            awaitHifi(underTest, () -> !underTest.hifiReason.peek().isEmpty());

            assertFalse(controller.hifiSupported.peek());
            assertFalse(controller.hifiBitPerfectActive.peek());
            assertEquals("", controller.hifiDeviceName.peek());
            assertEquals("", controller.hifiOutputFormat.peek());
            assertTrue(controller.hifiDevices.peek().isEmpty());

            // The settings rows call these whatever the backend is: desktop and
            // Android both go through the same SettingsCore wiring, so neither may
            // throw when the capability is absent.
            controller.setHifiEnabled(true);
            controller.setHifiFollowSampleRate(true);
            controller.setHifiOutputDevice("42");
            controller.refreshHifiDevices();
            controller.refreshHifiChain();
            awaitHifi(underTest, () -> !underTest.hifiReason.peek().isEmpty());
            assertTrue(controller.hifiEnabled.peek());
            assertFalse(controller.hifiSupported.peek());
        } finally {
            if (controller != null) controller.shutdownAsync().join();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void backendWithHifiControlPublishesTheChain() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("hifi-dac").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            HifiAudioBackend backend = new HifiAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            final PlayerController underTest = controller;

            controller.refreshHifiStatus();
            awaitHifi(underTest, () -> !underTest.hifiDeviceName.peek().isEmpty());

            assertTrue(controller.hifiSupported.peek());
            assertTrue(controller.hifiBitPerfectSupported.peek());
            assertTrue(controller.hifiUsbDevicePresent.peek());
            assertEquals("iBasso DC03", controller.hifiDeviceName.peek());
            assertEquals(AudioOutputDevice.ROUTE_USB, controller.hifiRouteType.peek());
            assertEquals("FLAC 24bit/96kHz", controller.hifiSourceFormat.peek());
            assertEquals("PCM 16bit/48kHz", controller.hifiOutputFormat.peek());
            assertTrue(controller.hifiResampled.peek());
            assertTrue(controller.hifiBitPerfectActive.peek());
            assertEquals("", controller.hifiReason.peek());
            assertEquals(2, controller.hifiDevices.peek().size());

            // Settings writes reach the backend through the remaining setters.
            controller.setHifiEnabled(true);
            controller.setHifiFollowSampleRate(false);
            controller.setHifiOutputDevice("7");
            assertEquals("7", backend.selectedDeviceId);
            assertFalse(backend.followSampleRate);
            assertTrue(backend.hifiEnabled);

            // There is no mode dropdown any more: the readouts come straight from
            // what the backend reports, which is SYSTEM or EXCLUSIVE only.
            backend.mode = HifiOutputMode.EXCLUSIVE;
            controller.refreshHifiChain();
            awaitHifi(underTest, () -> underTest.hifiEffectiveMode.peek()
                    == HifiOutputMode.EXCLUSIVE.id());
            assertEquals(HifiOutputMode.EXCLUSIVE.id(),
                    (int) controller.hifiOutputMode.peek());
            assertEquals(HifiOutputMode.EXCLUSIVE.id(),
                    (int) controller.hifiEffectiveMode.peek());
            assertEquals(AudioOutputStatus.EXCLUSIVE_FULL, controller.hifiExclusiveState.peek());
            assertEquals("", controller.hifiFallbackReason.peek());

            // Both refresh buttons re-query through the same machinery.
            controller.refreshHifiDevices();
            awaitHifi(underTest, () -> !underTest.hifiDeviceName.peek().isEmpty());
            assertEquals(2, controller.hifiDevices.peek().size());
        } finally {
            if (controller != null) controller.shutdownAsync().join();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /** Reading a stream's own header is part of the chain query, so the readout has
     *  to say "reading" while that read is in flight — and drop that the moment the
     *  answer lands, whatever the answer was. */
    @Test
    public void sourceFormatReadIsFlaggedWhileItRuns() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("hifi-probe").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            ProbeBackend backend = new ProbeBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            final PlayerController underTest = controller;

            controller.refreshHifiStatus();
            // The worker is inside outputStatus() (the read) right now: the flag has
            // to be up already, not only once the query returned.
            awaitHifi(underTest, () -> underTest.hifiSourceFormatPending.peek());
            assertTrue(controller.hifiSourceFormatPending.peek());
            assertEquals("", controller.hifiSourceFormat.peek());

            backend.releaseRead.countDown();
            awaitHifi(underTest, () -> !underTest.hifiSourceFormatPending.peek());
            assertFalse(controller.hifiSourceFormatPending.peek());
            assertEquals("FLAC 96kHz", controller.hifiSourceFormat.peek());
        } finally {
            if (controller != null) controller.shutdownAsync().join();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /** The perceptual curve is ours, so it belongs to the level we own: only the
     *  exclusive digital attenuation gets it. Everywhere else — the framework
     *  route, a fallback, desktop — the host's own plain linear app volume stands
     *  and a slider reads as the fraction it says. */
    @Test
    public void perceptualCurveAppliesOnlyToTheExclusiveDigitalLevel() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("hifi-curve").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            VolumeProbeBackend backend = new VolumeProbeBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);

            backend.digital = false;
            controller.setVolume(0.8f);
            assertEquals(0.8f, backend.level, 1e-6);

            backend.digital = true;
            controller.setVolume(0.8f);
            assertEquals((float) Math.pow(0.8, 3), backend.level, 1e-6);

            // Back to a level the host does not report as digital (a hardware end
            // or the framework route): linear again on the very next write.
            backend.digital = false;
            controller.setVolume(0.5f);
            assertEquals(0.5f, backend.level, 1e-6);
        } finally {
            if (controller != null) controller.shutdownAsync().join();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /** Each route keeps its own level: the number set while the app's own USB
     *  stream owns the output must not come back as the framework path's
     *  attenuation after the switch (and the other way round). */
    @Test
    public void exclusiveAndFrameworkLevelsStayIndependent() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("hifi-levels").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            VolumeProbeBackend backend = new VolumeProbeBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);

            // The framework route's own level.
            backend.exclusive = false;
            controller.setVolume(0.9f);
            assertEquals(0.9f, backend.level, 1e-6);

            // The exclusive route's own — and its default is half scale, not the
            // full scale an open used to inherit from the DAC's own control.
            backend.exclusive = true;
            controller.setHifiEnabled(true);
            assertEquals(0.5f, backend.level, 1e-6);
            controller.setVolume(0.4f);
            assertEquals(0.4f, backend.level, 1e-6);

            // Back on the framework route the exclusive number must not follow it:
            // the level handed to the sink is the framework's own 0.9, not 0.4.
            backend.exclusive = false;
            controller.setHifiEnabled(false);
            assertEquals(0.9f, backend.level, 1e-6);

            // And returning to the exclusive route restores its own 0.4.
            backend.exclusive = true;
            controller.setHifiEnabled(true);
            assertEquals(0.4f, backend.level, 1e-6);
        } finally {
            if (controller != null) controller.shutdownAsync().join();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /** The refresh runs off the render thread, so a test has to drain the UI
     *  queue itself — the app's own render loop is what normally does it. */
    private static void awaitHifi(PlayerController controller, Condition done)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS;
        while (!done.met() && System.currentTimeMillis() < deadline) {
            controller.pump();
            Thread.sleep(10L);
        }
        controller.pump();
    }

    private interface Condition {
        boolean met();
    }

    private static class FakeAudioBackend implements AudioBackend {
        boolean playing;

        @Override public void play(String source, long startMs) { playing = true; }
        @Override public void pause() { playing = false; }
        @Override public void resume() { playing = true; }
        @Override public boolean isPlaying() { return playing; }
        @Override public void seek(long ms) { }
        @Override public long position() { return 0L; }
        @Override public long duration() { return 0L; }
        @Override public void setVolume(float volume) { }
        @Override public void setOnComplete(Runnable callback) { }
        @Override public void release() { playing = false; }
    }

    /** A backend whose slider is only sometimes digital — the exclusive stream
     *  answers live, so the test can flip the answer and watch what reaches the
     *  sink. */
    private static final class VolumeProbeBackend extends FakeAudioBackend
            implements HifiOutputControl {
        boolean digital;
        boolean exclusive;
        float level = -1f;

        @Override public void setVolume(float volume) { level = volume; }

        @Override public boolean softwareVolumeActive() { return digital; }

        @Override public boolean exclusiveLevelActive() { return exclusive; }

        @Override public List<AudioOutputDevice> outputDevices() {
            return Arrays.asList(new AudioOutputDevice("", "自动（系统默认）",
                    AudioOutputDevice.ROUTE_USB, true));
        }

        @Override public AudioOutputStatus outputStatus() {
            return new AudioOutputStatus("", "", AudioOutputDevice.ROUTE_USB, "PCM", 48000, 16,
                    "PCM", 48000, 16, false, false, false, "", HifiOutputMode.SYSTEM,
                    HifiOutputMode.SYSTEM, AudioOutputStatus.EXCLUSIVE_NONE, "");
        }

        @Override public HifiCapabilities capabilities() {
            return new HifiCapabilities(false, false, false, 0, "");
        }

        @Override public void setHifiEnabled(boolean on) { }
        @Override public void setFollowSampleRate(boolean on) { }
        @Override public void selectOutputDevice(String deviceId) { }
    }

    /** A backend with the optional capability, reporting a 24/96 source that the
     *  framework mixes down to 16/48. */
    private static final class HifiAudioBackend extends FakeAudioBackend implements HifiOutputControl {
        String selectedDeviceId = "";
        boolean hifiEnabled;
        boolean followSampleRate = true;
        HifiOutputMode mode = HifiOutputMode.SYSTEM;

        @Override public List<AudioOutputDevice> outputDevices() {
            return Arrays.asList(
                    new AudioOutputDevice("", "自动（系统默认）", AudioOutputDevice.ROUTE_USB, true),
                    new AudioOutputDevice("7", "iBasso DC03", AudioOutputDevice.ROUTE_USB, true));
        }

        @Override public AudioOutputStatus outputStatus() {
            return new AudioOutputStatus("7", "iBasso DC03", AudioOutputDevice.ROUTE_USB,
                    "FLAC", 96000, 24, "PCM", 48000, 16, true, true, true, "",
                    mode, mode, AudioOutputStatus.EXCLUSIVE_FULL, "");
        }

        @Override public HifiCapabilities capabilities() {
            return new HifiCapabilities(false, true, true, 192, "");
        }

        @Override public void setHifiEnabled(boolean on) { hifiEnabled = on; }
        @Override public void setFollowSampleRate(boolean on) { followSampleRate = on; }
        @Override public void selectOutputDevice(String deviceId) { selectedDeviceId = deviceId; }
    }

    /** A backend whose chain query waits — the way a real one waits on a network
     *  header read — so the test can look at the readout mid-read, and whose source
     *  format only exists once that read returns. */
    private static final class ProbeBackend extends FakeAudioBackend implements HifiOutputControl {
        final CountDownLatch releaseRead = new CountDownLatch(1);

        @Override public boolean sourceFormatPending() { return true; }

        @Override public List<AudioOutputDevice> outputDevices() {
            return Arrays.asList(new AudioOutputDevice("", "自动（系统默认）",
                    AudioOutputDevice.ROUTE_USB, true));
        }

        @Override public AudioOutputStatus outputStatus() {
            try {
                releaseRead.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // A stream's codec and rate without a bit depth, which is what a network
            // source reads back as.
            return new AudioOutputStatus("", "自动（系统默认）", AudioOutputDevice.ROUTE_USB,
                    "FLAC", 96000, 0, "PCM", 48000, 16, true, false, false, "",
                    HifiOutputMode.SYSTEM, HifiOutputMode.SYSTEM,
                    AudioOutputStatus.EXCLUSIVE_NONE, "");
        }

        @Override public HifiCapabilities capabilities() {
            return new HifiCapabilities(false, false, false, 0, "");
        }

        @Override public void setHifiEnabled(boolean on) { }
        @Override public void setFollowSampleRate(boolean on) { }
        @Override public void selectOutputDevice(String deviceId) { }
    }
}
