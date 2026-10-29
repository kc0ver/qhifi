package dev.t1m3.qplayer.audio;

/**
 * 「胆味」音色的参数表，对应三种电子管的近似特性。
 *
 * <p>{@code harmonic} 是二次非线性项的系数，决定二次谐波电平。正弦幅度为 A 时，
 * {@code y + a·y²} 产生的二次分量幅度是 {@code a·A²/2}，相对基波为 {@code a·A/2}；
 * 也就是说 H2 随信号幅度线性增长，必须绑定一个参考电平才有意义。
 *
 * <p>这里统一取 A_ref = 0.5（约 -6dBFS，接近正常聆听电平的强信号段）标定：
 * {@code a = 4 × H2_rel}。因此 -35dB 对应 a ≈ 0.071，-38dB 对应 a ≈ 0.050。
 * 低于参考电平的节目素材谐波会自然减轻，这正是真实胆机的行为。
 *
 * <p>预设之间只差几个系数，但听感差别明显：300B 单端更甜厚（低频抬得高、
 * 高频滚得早），EL34 更通透，6L6 居中。
 */
public final class TubeTonePreset {

    public static final int COUNT = 3;

    /** 2A3/300B 一类单端三极管：最暖、低频最饱满、二次谐波最重。 */
    public static final TubeTonePreset TUBE_300B = new TubeTonePreset(
            "300b", 0.1127f, 0.93f, 150f, 2.0f, 16000f, -1.0f, -22f, 0.008f);

    /** EL34 推挽：干净、通透。谐波比 300B 低 6dB、倾斜几乎为零，这样
     *  "换管型"才是一耳朵能听出的差别，而不是只差 1.5dB 的纸上参数。 */
    public static final TubeTonePreset EL34 = new TubeTonePreset(
            "el34", 0.0565f, 0.96f, 100f, 0.8f, 20000f, 0f, -18f, 0.012f);

    /** 6L6 束射四极管：居中的一套。 */
    public static final TubeTonePreset TUBE_6L6 = new TubeTonePreset(
            "6l6", 0.0798f, 0.945f, 120f, 1.5f, 16000f, -0.6f, -20f, 0.010f);

    /** 存盘用的稳定标识（设置项按序号存，这里只给日志和调试命令用）。 */
    public final String id;
    /** 强度 100% 时的二次项系数 a。 */
    public final float harmonic;
    /** 软限幅的拐点：瞬时幅度超过它才开始压缩。真实录音的峰值常在 -1dBFS
     *  以上，拐点若压得太低（旧值 0.85 = -1.4dBFS），几乎每个强音都被压扁，
     *  动态塌陷后同样会有"被罩住"的感觉。现在的取值只保护接近满刻度的溢出——
     *  二次项把满刻度信号抬到 1+a，仍然需要这一级接住。 */
    public final float softClip;
    /** 低频架的转折频率与增益。 */
    public final float lowShelfHz;
    public final float lowShelfDb;
    /** 高频架的转折频率与增益（负值模拟输出变压器滚降）。 */
    public final float highShelfHz;
    public final float highShelfDb;
    /** "谐波随电平"的参考电平（dBFS）：低于它谐波减弱，高于它接近满强度。 */
    public final float envelopeRefDb;
    /** 缓慢漂移的幅度（相对 a 的比例），仅在漂移开关打开时生效。 */
    public final float driftRatio;

    private TubeTonePreset(String id, float harmonic, float softClip,
                           float lowShelfHz, float lowShelfDb,
                           float highShelfHz, float highShelfDb,
                           float envelopeRefDb, float driftRatio) {
        this.id = id;
        this.harmonic = harmonic;
        this.softClip = softClip;
        this.lowShelfHz = lowShelfHz;
        this.lowShelfDb = lowShelfDb;
        this.highShelfHz = highShelfHz;
        this.highShelfDb = highShelfDb;
        this.envelopeRefDb = envelopeRefDb;
        this.driftRatio = driftRatio;
    }

    /** 设置项里存的是序号，越界一律回到 300B，避免旧存档让播放静音。 */
    public static TubeTonePreset byIndex(int index) {
        switch (index) {
            case 1: return EL34;
            case 2: return TUBE_6L6;
            default: return TUBE_300B;
        }
    }

    /** 参考电平的线性幅度。 */
    public float envelopeRefLinear() {
        return (float) Math.pow(10f, envelopeRefDb / 20f);
    }
}
