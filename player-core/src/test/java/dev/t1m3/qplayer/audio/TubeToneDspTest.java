package dev.t1m3.qplayer.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 胆味内核的客观测量。这些数字是 P0 的准入门槛：音色可以主观调，
 * 但"谐波对不对"和"有没有混叠"必须能测出来。
 */
public class TubeToneDspTest {

    private static final int FS = 48000;
    /** 分析块长度：100ms @48k。1k/11k/15k/19k/20k 在 4800 点内都是整数周期，无泄露。 */
    private static final int BLOCK = 4800;
    /** 渲染总长：1 秒，前面的包络与参数平滑先稳定下来。 */
    private static final int TOTAL = 48000;

    @Test
    public void bypassIsBitExact() {
        float[] input = sine(FS, TOTAL, 1000, 0.5f);
        // 只配对参数、不 setEnabled，模拟"胆味开关关着"的真实状态。
        TubeToneDsp dsp = new TubeToneDsp(FS, 1, true);
        dsp.setPreset(TubeTonePreset.TUBE_300B);
        dsp.setTiltEnabled(false);
        dsp.setAmount(100);
        float[] out = input.clone();
        dsp.process(out, TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            assertEquals("关闭时不得改动任何样本", input[i], out[i], 0f);
        }
    }

    @Test
    public void secondHarmonicMatchesCalibration() {
        float[] out = render(TubeTonePreset.TUBE_300B, 100, sine(FS, TOTAL, 1000, 0.5f));
        double h1 = db(sineAmplitude(out, 1000));
        double h2 = db(sineAmplitude(out, 2000));
        // 幅度 A 的正弦过 y+a·y² 后，二次分量相对基波是 a·A/2。
        // 标定电平取 A=0.5(-6dBFS)，所以相对 H2 = 20log10(a/4)。
        double expected = db(TubeTonePreset.TUBE_300B.harmonic / 4.0);
        assertClose("H2 相对基波应等于标定值", expected, h2 - h1, 2.0);
        assertTrue("基波不应被明显压缩", h1 > -6.5);
        // 真实胆机 H3 低于 H2，软限幅应当只补一点点。
        double h3 = db(sineAmplitude(out, 3000));
        assertTrue("H3 应明显低于 H2", h3 < h2 - 6);
    }

    @Test
    public void aliasingStaysBelowFloor() {
        // 11kHz 的 3 次谐波是 33kHz，超出 24kHz 奈奎斯特，会折回 15kHz。
        float[] out = render(TubeTonePreset.TUBE_300B, 100, sine(FS, TOTAL, 11000, 0.5f));
        double alias = db(sineAmplitude(out, 15000));
        assertTrue("过采样后 15kHz 折叠峰应低于 -80dBFS，实测 " + alias, alias < -80.0);
    }

    @Test
    public void narrowBandAliasingIsWorseWithoutOversampling() {
        // 对照组：同样的失真，不过采样。用来证明这一级不是白加的。
        float[] input = sine(FS, TOTAL, 11000, 0.5f);
        TubeToneDsp raw = new TubeToneDsp(FS, 1, false);
        raw.setPreset(TubeTonePreset.TUBE_300B);
        raw.setTiltEnabled(false);
        raw.setAmount(100);
        raw.setEnabled(true);
        float[] out = input.clone();
        raw.process(out, TOTAL);
        double alias = db(sineAmplitude(out, 15000));
        assertTrue("不过采样时应当能测到混叠", alias > -80.0);
    }

    @Test
    public void envelopeKeepsQuietSignalClean() {
        float[] loud = render(TubeTonePreset.TUBE_300B, 100, sine(FS, TOTAL, 1000, 0.5f));
        float[] quiet = render(TubeTonePreset.TUBE_300B, 100, sine(FS, TOTAL, 1000, 0.00316f));
        double loudH2 = db(sineAmplitude(loud, 2000));
        double quietH2 = db(sineAmplitude(quiet, 2000));
        assertTrue("小信号谐波应至少低 15dB，实测差 " + (loudH2 - quietH2),
                loudH2 - quietH2 > 15.0);
    }

    @Test
    public void intermodulationStaysAcceptable() {
        float[] input = new float[TOTAL];
        for (int i = 0; i < TOTAL; i++) {
            double t = i / (double) FS;
            input[i] = (float) (0.25 * Math.sin(2 * Math.PI * 19000 * t)
                    + 0.25 * Math.sin(2 * Math.PI * 20000 * t));
        }
        float[] out = render(TubeTonePreset.TUBE_300B, 100, input);
        // 19k/20k 的差频落在 1kHz，这是双音测试最容易听到的"嗡"。
        double imd = db(sineAmplitude(out, 1000));
        assertTrue("1kHz 差频产物应低于 -42dBFS，实测 " + imd, imd < -42.0);
    }

    @Test
    public void passbandStaysFlatUpToTheTopOctave() {
        // 「罩在罩子里」的客观形态：高频整体被压低。过采样滤波器的通带滚降
        // 加上高频架，会让 12kHz 以上明显发暗，听感就是声音被包住。
        // 未开启 tilt 时，除谐波外信号只该被滤波器与 dcBlock 影响，所以
        // 这里测的是滤波器本身的通带平坦度。
        double ref = db(sineAmplitude(render(TubeTonePreset.TUBE_300B, 100,
                sine(FS, TOTAL, 1000, 0.25f)), 1000));
        // 整体电平不能被链路悄悄拉低：听觉上的"变闷"常常先来自整体变弱。
        assertEquals("1kHz 直通增益应接近 1", 0.0, ref - db(0.25f), 0.5);
        int[] freqs = {5000, 8000, 10000, 12000, 15000, 18000, 20000};
        StringBuilder report = new StringBuilder();
        for (int f : freqs) {
            double gain = db(sineAmplitude(render(TubeTonePreset.TUBE_300B, 100,
                    sine(FS, TOTAL, f, 0.25f)), f)) - ref;
            report.append(f).append("Hz ").append(round(gain)).append("  ");
            assertTrue(f + "Hz 通带增益应在 -1dB 内，实测 " + round(gain), gain > -1.0);
        }
    }

    @Test
    public void tiltShapesTheExtremesGently() {
        // 倾斜是"暖声"，不是"闷声"：低频略抬、高频略收，但整条曲线必须在
        // 听得出来的范围内保持克制。之前 11kHz/-2.2dB 起滚，叠上滤波器自身的
        // 滚降就成了"罩在罩子里"。
        double ref = db(tiltGain(1000));
        double low = db(tiltGain(100)) - ref;
        double mid = db(tiltGain(10000)) - ref;
        double high = db(tiltGain(20000)) - ref;

        assertTrue("100Hz 抬升应克制，实测 " + round(low), low < 2.2);
        assertTrue("10kHz 不应明显下陷，实测 " + round(mid), mid > -1.2);
        assertTrue("20kHz 不应明显下陷，实测 " + round(high), high > -3.0);
        System.out.println("tilt curve 100Hz " + round(low) + " / 1kHz 0dB / 10kHz "
                + round(mid) + " / 20kHz " + round(high));
    }

    @Test
    public void midStrengthMustNotNotchThePassband() {
        // 干湿混合最容易出的问题：wet 经过 dcBlock 与 biquad 后相位与 dry
        // 不同，半湿（55）相加会在高频互相抵消——听感就是"困在管子里"，
        // 而全湿（100）反而正常。这里逐频点扫一遍来抓它。
        double ref = db(strengthGain(55, 1000));
        int[] freqs = {3000, 5000, 8000, 10000, 14000, 18000, 20000};
        StringBuilder report = new StringBuilder();
        for (int f : freqs) {
            double gain = db(strengthGain(55, f)) - ref;
            report.append(f).append("Hz ").append(round(gain)).append("  ");
            assertTrue(f + "Hz 在强度 55 下不应凹陷，实测 " + round(gain), gain > -0.6);
        }
        System.out.println("strength 55 sweep: " + report);
    }

    @Test
    public void strengthActuallyScalesColoration() {
        // 强度必须真的改变染色量：0/55/100 三档的 H2 要拉开可见的间距。
        double h2at100 = h2of(100);
        double h2at55 = h2of(55);
        double h2at0 = h2of(0);
        assertTrue("强度 100 应有明显 H2", h2at100 > -45);
        assertTrue("强度 55 的 H2 应比 100 低 3dB 以上，实测差 "
                + round(h2at100 - h2at55), h2at100 - h2at55 > 3.0);
        assertTrue("强度 0 不得有任何染色，实测 " + round(h2at0), h2at0 < -90);
    }

    @Test
    public void passbandStaysFlatAt44100() {
        // 44.1kHz 是更常见的情况，归一化截止离 20kHz 只有 0.018，比 48k 更紧。
        double ref = db(gainAt(44100, 1000));
        int[] freqs = {10000, 14000, 18000, 20000};
        for (int f : freqs) {
            double gain = db(gainAt(44100, f)) - ref;
            assertTrue("44.1k 下 " + f + "Hz 通带增益应在 -1dB 内，实测 " + round(gain),
                    gain > -1.0);
        }
    }

    @Test
    public void presetsAreAudiblyDifferent() {
        // 「换管型听不出区别」是 P1 试听反馈。三个预设必须在谐波量上拉开
        // 足够间距（6dB 的 H2 差是一耳朵的事），而不是只差 1.5dB 的纸上参数。
        double b300 = h2of(TubeTonePreset.TUBE_300B, 100);
        double el34 = h2of(TubeTonePreset.EL34, 100);
        double l6 = h2of(TubeTonePreset.TUBE_6L6, 100);
        System.out.println("H2 @100: 300B " + round(b300) + " / EL34 " + round(el34)
                + " / 6L6 " + round(l6));
        assertTrue("300B 应比 EL34 厚至少 5dB，实测差 " + round(b300 - el34), b300 - el34 > 5.0);
        // 300B 最厚、EL34 最轻，6L6 居中。
        assertTrue("6L6 应居中", b300 > l6 && l6 > el34);
    }

    @Test
    public void driftMakesTheCoefficientBreathe() {
        // 漂移按设计只有 ±1%，耳朵本来就几乎听不出来；这里证明它在动，
        // 以及动多大——用户可以用调试命令反复读 a 来确认同一件事。
        TubeToneDsp dsp = new TubeToneDsp(FS, 1, true);
        dsp.setPreset(TubeTonePreset.TUBE_300B);
        dsp.setTiltEnabled(false);
        dsp.setAmount(100);
        dsp.setDriftEnabled(true);
        dsp.setEnabled(true);

        int chunk = FS;                       // 1 秒一块
        int chunks = 40;                      // 40 秒 > 慢 LFO 的一个周期
        float[] buf = new float[chunk];
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int c = 0; c < chunks; c++) {
            // 每块重新填 100Hz 正弦：恒定电平让包络停在满调制，只看漂移。
            // 必须先填再处理——process 是就地改写，反过来会把输出当输入。
            for (int i = 0; i < chunk; i++) {
                buf[i] = (float) (0.3 * Math.sin(2 * Math.PI * 100 * (c * chunk + i) / FS));
            }
            dsp.process(buf, chunk);
            float a = dsp.currentNonlinearity();
            if (c < 2) continue;              // 跳过包络与平滑的起步
            if (a < min) min = a;
            if (a > max) max = a;
        }
        float range = max - min;
        System.out.println("drift a range: " + String.format(java.util.Locale.US,
                "%.5f .. %.5f (%.3f%%)", min, max, range / max * 100f));
        assertTrue("漂移应让系数有可见的摆动，实测幅度 " + range, range > 0.0008f);
        assertTrue("漂移幅度应保持克制（不超过 5%）", range / max < 0.05f);
    }

    @Test
    public void outputNeverExceedsHeadroomOnFullScaleInput() {
        // 真机实测：开胆味后每 2 秒窗口都有样本撞 16-bit 满刻度（关掉后
        // 峰值稳定在 0.92）。成因是降采样 FIR 的 Gibbs 过冲叠上低频架抬峰。
        // 48k 域最后一级保险必须把输出钉在 0.97 以内，否则 16-bit 写回
        // 会硬钳到 32767，听感是偶发的高频毛糙。
        float[] out = new float[TOTAL];
        for (int i = 0; i < TOTAL; i++) {
            out[i] = (float) (0.99 * Math.sin(2 * Math.PI * 1000 * i / FS));
        }
        TubeToneDsp dsp = new TubeToneDsp(FS, 1, true);
        dsp.setPreset(TubeTonePreset.TUBE_300B);
        dsp.setTiltEnabled(true);
        dsp.setAmount(100);
        dsp.setEnabled(true);
        dsp.process(out, TOTAL);
        float max = 0f;
        for (float v : out) {
            float a = v < 0f ? -v : v;
            if (a > max) max = a;
        }
        assertTrue("满刻度输入不得把输出推出 0.97，实测峰值 " + max, max <= 0.971f);
    }

    // --- 工具 -------------------------------------------------------------

    /** 强度 percent、tilt 关，单频正弦经由整条链后的幅度。 */
    private static double strengthGain(int percent, double freq) {
        TubeToneDsp dsp = new TubeToneDsp(FS, 1, true);
        dsp.setPreset(TubeTonePreset.TUBE_300B);
        dsp.setTiltEnabled(false);
        dsp.setAmount(percent);
        dsp.setEnabled(true);
        float[] out = sine(FS, TOTAL, freq, 0.25f);
        dsp.process(out, TOTAL);
        return sineAmplitude(out, freq);
    }

    /** 强度 percent 时的 H2 相对基波（dB）。 */
    private static double h2of(int percent) {
        return h2of(TubeTonePreset.TUBE_300B, percent);
    }

    private static double h2of(TubeTonePreset preset, int percent) {
        TubeToneDsp dsp = new TubeToneDsp(FS, 1, true);
        dsp.setPreset(preset);
        dsp.setTiltEnabled(false);
        dsp.setAmount(percent);
        dsp.setEnabled(true);
        float[] out = sine(FS, TOTAL, 1000, 0.5f);
        dsp.process(out, TOTAL);
        return db(sineAmplitude(out, 2000)) - db(sineAmplitude(out, 1000));
    }

    /** 任意采样率下的单频幅度（tilt 关、强度 100）。 */
    private static double gainAt(int fs, double freq) {
        int total = fs;
        int block = fs / 10;
        TubeToneDsp dsp = new TubeToneDsp(fs, 1, true);
        dsp.setPreset(TubeTonePreset.TUBE_300B);
        dsp.setTiltEnabled(false);
        dsp.setAmount(100);
        dsp.setEnabled(true);
        float[] out = new float[total];
        for (int i = 0; i < total; i++) {
            out[i] = (float) (0.25 * Math.sin(2 * Math.PI * freq * i / fs));
        }
        dsp.process(out, total);
        return goertzel(out, fs, block, freq);
    }

    private static double goertzel(float[] buf, int fs, int block, double freq) {
        int offset = buf.length - block;
        double w = 2 * Math.PI * freq / fs;
        double coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < block; i++) {
            double s0 = buf[offset + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * Math.cos(w);
        double imag = s2 * Math.sin(w);
        return 2 * Math.sqrt(real * real + imag * imag) / block;
    }

    /** 300B 预设、tilt 开着、强度满，单频正弦经由整条链后的幅度。 */
    private static double tiltGain(double freq) {
        TubeToneDsp dsp = new TubeToneDsp(FS, 1, true);
        dsp.setPreset(TubeTonePreset.TUBE_300B);
        dsp.setTiltEnabled(true);
        dsp.setAmount(100);
        dsp.setEnabled(true);
        float[] out = sine(FS, TOTAL, freq, 0.25f);
        dsp.process(out, TOTAL);
        return sineAmplitude(out, freq);
    }

    private static TubeToneDsp newDsp(TubeTonePreset preset, int amount) {
        TubeToneDsp dsp = new TubeToneDsp(FS, 1, true);
        dsp.setPreset(preset);
        dsp.setTiltEnabled(false);
        dsp.setAmount(amount);
        dsp.setEnabled(true);
        return dsp;
    }

    private static float[] render(TubeTonePreset preset, int amount, float[] input) {
        float[] out = input.clone();
        newDsp(preset, amount).process(out, TOTAL);
        return out;
    }

    /** 单声道正弦，幅度为峰值。 */
    private static float[] sine(int fs, int frames, double freq, float amp) {
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            out[i] = (float) (amp * Math.sin(2 * Math.PI * freq * i / fs));
        }
        return out;
    }

    /** Goertzel 单频幅度（正弦峰值）。取末尾 BLOCK 点，信号已进入稳态。 */
    private static double sineAmplitude(float[] buf, double freq) {
        int offset = buf.length - BLOCK;
        double w = 2 * Math.PI * freq / FS;
        double coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < BLOCK; i++) {
            double s0 = buf[offset + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * Math.cos(w);
        double imag = s2 * Math.sin(w);
        return 2 * Math.sqrt(real * real + imag * imag) / BLOCK;
    }

    private static double db(double amplitude) {
        return amplitude <= 0 ? -300 : 20 * Math.log10(amplitude);
    }

    private static void assertClose(String message, double expected, double actual, double tolerance) {
        assertEquals(message + "（期望 " + round(expected) + "，实测 " + round(actual) + "）",
                expected, actual, tolerance);
    }

    private static String round(double v) {
        return String.format(java.util.Locale.US, "%.1fdB", v);
    }
}
