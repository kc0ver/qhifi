package dev.t1m3.qplayer.android.playback;

import dev.t1m3.qplayer.audio.TubeToneDsp;
import dev.t1m3.qplayer.audio.TubeTonePreset;
import dev.t1m3.qplayer.util.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * 「胆味」在 USB 独占路径上的接入点：把 {@link UsbIsoSink} 即将送上 USB 的
 * PCM 块就地改写。
 *
 * <p>它不是 {@code PcmSink} 的装饰器。{@link AndroidDirectAudioBackend} 里有十几处
 * {@code sink instanceof UsbIsoSink} 的强转（硬件音量、UAC 版本、DAC 是否同一个），
 * 包一层会让这些判断全部落空，音量模式和链路卡片会静默出错。所以这里挂在 sink
 * <em>内部</em>的写入路径上：布局没变，只是多了一次块处理。
 *
 * <p>容器格式跟着运行中的 sink 走，而不是 open 的入参——{@code UacOutput} 可能把
 * float 改成 16-bit 接受，{@link UsbIsoSink#open} 里读的就是它改写后的值。
 *
 * <p>参数由设置项经 {@code SettingsCore → PlayerController →
 * HifiAudioDispatcher#setTubeTone} 推下来（P1）；adb 广播
 * （{@link HifiAudioDispatcher#debugTube}）写同一组静态字段，作为无 UI 的调试覆盖。
 */
final class TubeToneSink {

    /** 关闭时不分配、不计算，{@link UsbIsoSink#write} 走原来的直通路径。 */
    private static volatile boolean enabled;
    private static volatile int presetIndex = 0;
    private static volatile int amount = 55;
    private static volatile boolean tilt = true;
    private static volatile boolean drift = false;
    /** 最近一个块的实际非线性系数，只给诊断命令看（能看出漂移在动）。 */
    private static volatile float lastA;
    /** 诊断覆盖：非 0 时绕过强度与包络，用来确认链路真的在改动样本。 */
    private static volatile float forceA;

    private TubeToneDsp dsp;
    private int channels = 2;
    private boolean floatPcm = true;
    private float[] block = new float[0];
    private ByteBuffer scratch;
    private boolean active;
    /** xorshift32：TPDF dither 只需要一个便宜、无分配、可复现的白噪声源。 */
    private int rng = 0x9e3779b9;

    /** sink 每次 open 后调用：换采样率/声道/容器都必须重建，状态从头开始。 */
    synchronized void configure(int sampleRate, int channelCount, boolean floatContainer) {
        channels = channelCount >= 1 ? channelCount : 1;
        floatPcm = floatContainer;
        block = new float[0];
        dsp = new TubeToneDsp(sampleRate, channels, true);
        dsp.setPreset(TubeTonePreset.byIndex(presetIndex));
        dsp.setTiltEnabled(tilt);
        dsp.setDriftEnabled(drift);
        dsp.setAmount(amount);
        dsp.setEnabled(enabled);
        active = false;
    }

    /** seek、切曲、重开流之后清空滤波器与包络，避免旧状态留在新时间基上。 */
    synchronized void reset() {
        if (dsp != null) dsp.reset();
        active = false;
    }

    /**
     * 处理 {@code src} 从当前位置起的 {@code bytes} 个样本容器。
     *
     * @return 该送进驱动的缓冲。关闭时原样返回 {@code src}（零拷贝，路径与没有胆味
     *         时完全一致）；开着时返回内部复制件。
     *
     * <p>必须返回复制件而不能就地改写：解码器给的输出缓冲不保证可写，而
     * {@code UacOutput.write} 走的是 {@code GetDirectBufferAddress}，只看基地址、
     * 不看 position/limit——复制件把处理后的数据放在偏移 0，正好对上。
     */
    synchronized ByteBuffer process(ByteBuffer src, int bytes) {
        TubeToneDsp d = dsp;
        if (d == null || bytes <= 0) return src;

        int bps = floatPcm ? 4 : 2;
        int frames = bytes / (bps * channels);
        if (frames <= 0) return src;

        if (!enabled || amount <= 0) {
            if (active) {
                d.reset();
                active = false;
            }
            return src;
        }
        if (!active) {
            d.reset();
            active = true;
        }
        // 每块重新同步一次参数：这几步在值没变时是空操作。
        d.setPreset(TubeTonePreset.byIndex(presetIndex));
        d.setTiltEnabled(tilt);
        d.setDriftEnabled(drift);
        d.setAmount(amount);
        d.setOverrideA(forceA);
        d.setEnabled(true);

        int samples = frames * channels;
        if (block.length < samples) block = new float[samples];
        if (scratch == null || scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocateDirect(bytes);
        }
        int start = src.position();
        ByteBuffer view = src.duplicate();
        view.position(start);
        view.limit(start + bytes);
        scratch.clear();
        scratch.put(view);
        scratch.order(ByteOrder.LITTLE_ENDIAN);
        scratch.position(0).limit(bytes);

        if (floatPcm) {
            FloatBuffer fb = scratch.asFloatBuffer();
            fb.get(block, 0, samples);
            d.process(block, frames);
            fb.put(block, 0, samples);
        } else {
            ShortBuffer sb = scratch.asShortBuffer();
            for (int i = 0; i < samples; i++) block[i] = sb.get(i) / 32768f;
            d.process(block, frames);
            for (int i = 0; i < samples; i++) {
                // 16-bit 路径本来就是非 bit-perfect，TPDF dither 会把量化
                // 相关的颗粒感换成恒定的白噪底，安静段落明显更干净。
                // float 路径没有量化步骤，不加。
                float v = block[i] * 32768f + dither16();
                if (v > 32767f) v = 32767f;
                else if (v < -32768f) v = -32768f;
                sb.put(i, (short) Math.round(v));
            }
        }
        syncDiagnostics();
        return scratch;
    }

    /** 三角分布 dither，峰值 ±1 LSB（以 16-bit 满刻度为单位）。两次独立
     *  均匀分布相加减半，就是标准的 TPDF：量化误差的均值与方差都被抹平。 */
    private float dither16() {
        float a = (nextRandom() >>> 8) * (1f / 8388608f) - 1f;
        float b = (nextRandom() >>> 8) * (1f / 8388608f) - 1f;
        return (a + b) * 0.5f;
    }

    private int nextRandom() {
        int x = rng;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rng = x;
        return x;
    }

    /** 处理完一个块后刷新诊断读数（每块一次，与音频线程同一线程写）。 */
    private void syncDiagnostics() {
        lastA = dsp != null ? dsp.currentNonlinearity() : 0f;
    }

    // --- 参数写入（设置项与 adb 广播共用） ----------------------------------

    static void setEnabled(boolean value) {
        enabled = value;
    }

    static void setPresetIndex(int index) {
        presetIndex = index;
    }

    static void setAmount(int percent) {
        amount = percent < 0 ? 0 : Math.min(percent, 100);
    }

    static void setTilt(boolean value) {
        tilt = value;
    }

    static void setDrift(boolean value) {
        drift = value;
    }

    static void setForceA(float value) {
        forceA = value < 0f ? 0f : value;
    }

    static String stateText() {
        return "enabled=" + enabled + " preset=" + TubeTonePreset.byIndex(presetIndex).id
                + " amount=" + amount + " tilt=" + tilt + " drift=" + drift
                + " a=" + String.format(java.util.Locale.US, "%.4f", lastA)
                + (forceA > 0f ? " FORCED=" + forceA : "");
    }

    static void logState() {
        Logger.info("tube {}", stateText());
    }
}
