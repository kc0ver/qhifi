package dev.t1m3.qplayer.audio;

/**
 * 「胆味」音染内核：把 float 交错 PCM 处理成带真空管风味的信号。
 *
 * <p>这不是均衡器，是一套刻意的不保真。信号链：
 * <pre>
 *   x  →  2 倍上采样  →  y + a·y² + b·y³（偶次为主、少量奇次）
 *      →  拐点软限幅  →  20Hz 一阶高通（去非线性产生的直流）
 *      →  2 倍降采样  →  低/高频架
 * </pre>
 *
 * <p>强度不靠干湿叠加实现，而是直接缩放非线性系数 {@code a}。干湿混合在
 * 半湿时会让 dry 与相位偏移后的 wet 在高频互相抵消（"困在管子里"），而且
 * {@code a→0} 时波形整形本来就退化为线性，不需要混合。
 *
 * <p>二次项是偶次谐波的来源，但它会让峰值越界（x=1 时变成 1+a），所以后面必须跟
 * 软限幅；限幅本身会补上少量三次谐波，正好接近真实胆机的谐波分布。二次项产生的
 * 直流分量正比于信号功率，必须用高通去掉，否则低电平会把 DAC 的工作点推走。
 *
 * <p>「谐波随电平」是像不像的关键：静态失真是插件感的主要来源，真实胆机在小信号下
 * 相当干净，厚暖感只在中高电平浮现。
 *
 * <p>本类只处理 float 交错数组，不认识 16-bit 容器；量化与 dither 由调用方负责。
 * 关闭时 {@link #process} 直接返回，不做任何运算。
 */
public final class TubeToneDsp {

    /** 半带滤波器的抽头数。32 抽头的通带只到 15kHz 左右，会把人声以上的
     *  空气感整体压低——听感是"罩在罩子里"。96 抽头把 -1dB 点推到 20kHz 以上，
     *  48kHz 立体声下单核仍在 2% 量级。 */
    private static final int TAPS = 96;
    /** 相对 2 倍采样率的归一化截止频率（0.25 对应原信号的奈奎斯特）。取
     *  0.245 让通带盖满 0-20kHz；插零镜像在 0.25 以上，96 抽头足以压住。 */
    private static final float CUTOFF = 0.245f;

    /** 每多少帧刷新一次目标系数（避免每样本算 pow，48kHz 下约 1.5ms 一次）。 */
    private static final int TARGET_REFRESH = 32;
    /** 参数平滑的每样本步长，约合 20ms 时间常数，防止拖滑块时爆音。 */
    private static final float SMOOTHING = 0.0008f;

    /** 最终保险的拐点与上限：降采样 FIR 对瞬态有约 7% 的过冲（Gibbs），
     *  低频架还会再抬，所以软限幅之后仍会有样本越过满刻度、被 16-bit 写回
     *  硬钳到 32767。这一级放在 48k 域的最后，保证送出去的样本永远 ≤ 0.97
     *  (-0.26dBFS)，且对正常信号几乎不介入。 */
    private static final float FINAL_KNEE = 0.90f;
    private static final float FINAL_CEIL = 0.97f;

    /** 峰值跟踪的攻击/释放时间常数（毫秒）。 */
    private static final float ENVELOPE_ATTACK_MS = 20f;
    private static final float ENVELOPE_RELEASE_MS = 200f;
    /** 包络调制的最低保留比例：极安静处也不是完全死板。 */
    private static final float ENVELOPE_FLOOR = 0.22f;
    /** 包络的调制指数。0.7 掉得太快——音乐的平均电平只有 -20~-30dBFS，
     *  那一档的染色会轻到听不出来；0.55 让中等电平也保留大部分染色。 */
    private static final float ENVELOPE_EXPONENT = 0.55f;

    /** 软限幅的渐近上限：留 0.02 的余量，避免二次项的抬升把样本推出满刻度。 */
    private static final float CEILING = 0.98f;
    /** 三次项相对二次项的固定比例。它让 H3 随电平平方增长，比 H2 掉得更快。 */
    private static final float CUBIC_RATIO = 0.45f;

    private final int channels;
    private final int sampleRate;
    private final boolean oversample;
    private final Voice[] voices;

    private final float attackCoef;
    private final float releaseCoef;
    private final float dcAlpha;

    private TubeTonePreset preset = TubeTonePreset.TUBE_6L6;
    private float amount = 0.55f;
    private boolean tilt = true;
    private boolean drift;
    private boolean enabled;

    /** 跨声道共享的峰值包络（线性幅度）。 */
    private float envelope;
    private float aNow;
    private float aTarget;
    /** 诊断覆盖值，非 0 时绕过强度与包络，直接当作系数使用。 */
    private float overrideA;
    /** 平滑后的强度（0..1），只用于把软限幅拐点一起收放；染色量本身由
     *  {@link #aTarget} 承载。两者都走同一时间常数，避免拖滑块时爆音。 */
    private float mixNow;
    private float mixTarget;
    private int frameCounter;
    private long sampleClock;

    /**
     * @param sampleRate 原始采样率（内部按 2 倍做非线性）
     * @param channels   声道数，交错排列
     * @param oversample 是否 2 倍过采样。关闭可省 CPU，但强失真时混叠会跑进可闻区。
     */
    public TubeToneDsp(int sampleRate, int channels, boolean oversample) {
        this.sampleRate = sampleRate < 8000 ? 8000 : sampleRate;
        this.channels = channels < 1 ? 1 : channels;
        this.oversample = oversample;
        this.voices = new Voice[this.channels];
        for (int i = 0; i < this.channels; i++) voices[i] = new Voice();

        this.attackCoef = (float) (1.0 - Math.exp(-1.0 / (this.sampleRate * ENVELOPE_ATTACK_MS / 1000.0)));
        this.releaseCoef = (float) (1.0 - Math.exp(-1.0 / (this.sampleRate * ENVELOPE_RELEASE_MS / 1000.0)));
        this.dcAlpha = (float) (1.0 - Math.exp(-2 * Math.PI * 20.0 / (this.sampleRate * (oversample ? 2 : 1))));
        this.mixNow = amount;
        this.mixTarget = amount;
        rebuildShelves();
    }

    public boolean enabled() {
        return enabled;
    }

    /** 诊断用的强制覆盖系数（0 = 不覆盖）。用来在真机上确认链路确实在改动
     *  样本：把它设成远大于标定值的数，失真应当立刻变得刺耳。 */
    public void setOverrideA(float value) {
        overrideA = value < 0f ? 0f : value;
    }

    /** 当前生效的非线性系数（已含强度、包络调制与漂移）。诊断用：读数在
     *  缓慢变化，就说明漂移/包络在起作用；为 0 表示此刻是纯线性直通。 */
    public float currentNonlinearity() {
        return aNow;
    }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value) reset();
    }

    public void setPreset(TubeTonePreset value) {
        if (value == null || value == preset) return;
        preset = value;
        rebuildShelves();
    }

    /** 强度 0..100。0 等同于关闭，100 时谐波电平等于预设标定值。 */
    public void setAmount(int percent) {
        float value = percent <= 0 ? 0f : (percent >= 100 ? 1f : percent / 100f);
        if (value == amount) return;
        amount = value;
        mixTarget = value;
        if (value <= 0f) enabled = false;
    }

    public void setTiltEnabled(boolean value) {
        if (tilt == value) return;
        tilt = value;
        rebuildShelves();
    }

    /** 极缓慢的参数游走，让机器"呼吸"。默认关闭，因为每次播放都不再可复现。 */
    public void setDriftEnabled(boolean value) {
        drift = value;
    }

    /**
     * 处理一个 float 交错块，就地改写。
     *
     * <p>关闭或强度为 0 时立即返回，不做任何运算——这是"bit-perfect 路径一行没碰过"
     * 的落点。
     */
    public void process(float[] buf, int frames) {
        if (!enabled || amount <= 0f || frames <= 0) return;

        for (int f = 0; f < frames; f++) {
            int base = f * channels;

            float peak = 0f;
            for (int c = 0; c < channels; c++) {
                float v = buf[base + c];
                if (v < 0f) v = -v;
                if (v > peak) peak = v;
            }
            envelope += (peak - envelope) * (peak > envelope ? attackCoef : releaseCoef);

            frameCounter++;
            if ((frameCounter & (TARGET_REFRESH - 1)) == 0) refreshTargets();
            aNow += (aTarget - aNow) * SMOOTHING;
            mixNow += (mixTarget - mixNow) * SMOOTHING;
            sampleClock++;

            for (int c = 0; c < channels; c++) {
                Voice voice = voices[c];
                float dry = buf[base + c];
                // 强度直接缩放非线性系数，绝不做 dry/wet 时域叠加：wet 经过
                // dcBlock 与 biquad 后相位与 dry 不同，半湿相加会在高频互相
                // 抵消（8kHz 实测 -1.2dB），听感就是"声音被困在管子里"，而
                // 全湿反而正常。a=0 时波形整形退化为纯线性，本来也不需要混合。
                float a = aNow;
                float knee = CEILING - (CEILING - preset.softClip) * mixNow;
                float wet;

                if (oversample) {
                    voice.push(dry);
                    float u0 = voice.upsampleEven();
                    float u1 = voice.upsampleOdd();
                    u0 = voice.dcBlock(shape(u0, a, knee), dcAlpha);
                    u1 = voice.dcBlock(shape(u1, a, knee), dcAlpha);
                    wet = voice.downsample(u0, u1);
                } else {
                    wet = voice.dcBlock(shape(dry, a, knee), dcAlpha);
                }

                // 频响倾斜属于管型的"声底"，不随强度缩水：否则强度 55 时
                // 300B 的高频架只剩 -0.55dB，开关几乎听不出来。
                wet = voice.lowShelf.process(wet);
                wet = voice.highShelf.process(wet);
                buf[base + c] = finalLimit(wet);
            }
        }
    }

    /** 清空全部滤波与包络状态。seek、切曲、重开 sink、开关切换后必须调用。 */
    public void reset() {
        for (Voice voice : voices) voice.reset();
        envelope = 0f;
        aNow = 0f;
        mixNow = amount;
        mixTarget = amount;
        frameCounter = 0;
        refreshTargets();
    }

    /**
     * 非线性本身：二次项（偶次谐波）+ 三次项（少量奇次谐波）+ 软限幅。
     *
     * <p>限幅只在 {@code |v| > knee} 时才介入：正常电平下它是完全线性的，不会把
     * H3 抬到 H2 之上；只有在接近满刻度时才慢慢压向 {@link #CEILING}。
     */
    private static float shape(float s, float a, float knee) {
        float s2 = s * s;
        float v = s + a * s2 + (CUBIC_RATIO * a) * s2 * s;
        float av = v < 0f ? -v : v;
        if (av <= knee) return v;
        float over = (av - knee) / (CEILING - knee);
        if (over > 6f) over = 6f;
        float limited = knee + (CEILING - knee) * tanh(over);
        return v < 0f ? -limited : limited;
    }

    /** 48k 域的最终保险：正常信号线性通过，只有靠近满刻度的样本被平滑收住。
     *  实测开胆味时每个 2 秒窗口都有样本撞 16-bit 上限（FIR 过冲 + 低频架抬峰），
     *  这是"偶发削顶"的来源，也会让高频听起来毛糙。 */
    private static float finalLimit(float x) {
        float av = x < 0f ? -x : x;
        if (av <= FINAL_KNEE) return x;
        float over = (av - FINAL_KNEE) / (FINAL_CEIL - FINAL_KNEE);
        if (over > 6f) over = 6f;
        float limited = FINAL_KNEE + (FINAL_CEIL - FINAL_KNEE) * tanh(over);
        return x < 0f ? -limited : limited;
    }

    /** 有理式 tanh 逼近，误差在 |x|≤3 内可忽略。超出后 Pade 逼近会越过 1
     *  （x→∞ 时趋于 x/9），所以这里必须自己夹住上限。 */
    private static float tanh(float x) {
        float x2 = x * x;
        float y = x * (27f + x2) / (27f + 9f * x2);
        return y > 1f ? 1f : y;
    }

    private void refreshTargets() {
        float ref = preset.envelopeRefLinear();
        float mod = 1f;
        if (ref > 0f) {
            float ratio = envelope / ref;
            if (ratio > 4f) ratio = 4f;
            mod = (float) Math.pow(ratio, ENVELOPE_EXPONENT);
            if (mod < ENVELOPE_FLOOR) mod = ENVELOPE_FLOOR;
            if (mod > 1f) mod = 1f;
        }
        if (drift) {
            double t = sampleClock / (double) sampleRate;
            float lfo = (float) ((Math.sin(2 * Math.PI * 0.031 * t)
                    + Math.sin(2 * Math.PI * 0.071 * t)) * 0.5);
            mod *= 1f + preset.driftRatio * lfo;
        }
        aTarget = overrideA > 0f ? overrideA : preset.harmonic * mod * amount;
    }

    private void rebuildShelves() {
        for (Voice voice : voices) {
            if (tilt) {
                voice.lowShelf.lowShelf(sampleRate, preset.lowShelfHz, preset.lowShelfDb, 0.707f);
                // 0dB 的高频架在数学上是恒等滤波，但递归式恒等滤波会留下
                // 数值误差；直接旁路更干净（EL34 就是"不加高频滚降"）。
                if (preset.highShelfDb == 0f) voice.highShelf.bypass();
                else voice.highShelf.highShelf(sampleRate, preset.highShelfHz, preset.highShelfDb, 0.707f);
            } else {
                voice.lowShelf.bypass();
                voice.highShelf.bypass();
            }
        }
    }

    /** 每个声道的滤波状态。 */
    private static final class Voice {
        private final float[] up = new float[TAPS];
        private int upPos;
        private final float[] down = new float[2 * TAPS];
        private int downPos;
        private float dcPrevIn;
        private float dcPrevOut;

        private static final float[] PROTOTYPE = designPrototype();

        void reset() {
            java.util.Arrays.fill(up, 0f);
            java.util.Arrays.fill(down, 0f);
            upPos = 0;
            downPos = 0;
            dcPrevIn = 0f;
            dcPrevOut = 0f;
            lowShelf.reset();
            highShelf.reset();
        }

        final Biquad lowShelf = new Biquad();
        final Biquad highShelf = new Biquad();

        void push(float x) {
            up[upPos] = x;
            upPos = (upPos + 1) % TAPS;
        }

        /** 插零上采样的偶数相位（对应原始时刻）。 */
        float upsampleEven() {
            float acc = 0f;
            for (int j = 0; j < TAPS / 2; j++) {
                acc += 2f * PROTOTYPE[2 * j] * sampleUp(j);
            }
            return acc;
        }

        /** 插零上采样的奇数相位（两个原始时刻之间）。 */
        float upsampleOdd() {
            float acc = 0f;
            for (int j = 0; j < TAPS / 2; j++) {
                acc += 2f * PROTOTYPE[2 * j + 1] * sampleUp(j);
            }
            return acc;
        }

        private float sampleUp(int j) {
            int idx = upPos - 1 - j;
            while (idx < 0) idx += TAPS;
            return up[idx % TAPS];
        }

        /** 把两个 2 倍样本送入降采样滤波器，返回抽取后的一个样本。 */
        float downsample(float s0, float s1) {
            down[downPos] = s0;
            downPos = (downPos + 1) % down.length;
            down[downPos] = s1;
            downPos = (downPos + 1) % down.length;

            float acc = 0f;
            int idx = downPos - 1;
            for (int k = 0; k < TAPS; k++) {
                int i = idx - k;
                while (i < 0) i += down.length;
                acc += PROTOTYPE[k] * down[i % down.length];
            }
            return acc;
        }

        /** 一阶高通：y[n] = x[n] - x[n-1] + R·y[n-1]，R 决定转折频率。alpha = 1 - R。 */
        float dcBlock(float x, float alpha) {
            float y = (x - dcPrevIn) + (1f - alpha) * dcPrevOut;
            dcPrevIn = x;
            dcPrevOut = y;
            return y;
        }

        /** 单位增益、通带平坦的低通原型，归一化到直流增益 1。 */
        private static float[] designPrototype() {
            float[] h = new float[TAPS];
            double mid = (TAPS - 1) / 2.0;
            float sum = 0f;
            for (int i = 0; i < TAPS; i++) {
                double t = i - mid;
                double sinc = Math.abs(t) < 1e-9
                        ? 2 * CUTOFF
                        : Math.sin(2 * Math.PI * CUTOFF * t) / (Math.PI * t);
                double w = 0.42
                        - 0.5 * Math.cos(2 * Math.PI * i / (TAPS - 1))
                        + 0.08 * Math.cos(4 * Math.PI * i / (TAPS - 1));
                h[i] = (float) (sinc * w);
                sum += h[i];
            }
            for (int i = 0; i < TAPS; i++) h[i] /= sum;
            return h;
        }
    }

    /** RBJ 双二阶滤波器，系数在构造时归一化到 a0 = 1。 */
    static final class Biquad {
        private float b0 = 1f;
        private float b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        void bypass() {
            b0 = 1f;
            b1 = b2 = a1 = a2 = 0f;
            reset();
        }

        void reset() {
            x1 = x2 = y1 = y2 = 0f;
        }

        void lowShelf(float fs, float f, float db, float q) {
            double a = Math.pow(10, db / 40.0);
            double w0 = 2 * Math.PI * f / fs;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2 * q);
            double sa = Math.sqrt(a);
            set(a * ((a + 1) - (a - 1) * cos + 2 * sa * alpha),
                    2 * a * ((a - 1) - (a + 1) * cos),
                    a * ((a + 1) - (a - 1) * cos - 2 * sa * alpha),
                    (a + 1) + (a - 1) * cos + 2 * sa * alpha,
                    -2 * ((a - 1) + (a + 1) * cos),
                    (a + 1) + (a - 1) * cos - 2 * sa * alpha);
        }

        void highShelf(float fs, float f, float db, float q) {
            double a = Math.pow(10, db / 40.0);
            double w0 = 2 * Math.PI * f / fs;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2 * q);
            double sa = Math.sqrt(a);
            set(a * ((a + 1) + (a - 1) * cos + 2 * sa * alpha),
                    -2 * a * ((a - 1) + (a + 1) * cos),
                    a * ((a + 1) + (a - 1) * cos - 2 * sa * alpha),
                    (a + 1) - (a - 1) * cos + 2 * sa * alpha,
                    2 * ((a - 1) - (a + 1) * cos),
                    (a + 1) - (a - 1) * cos - 2 * sa * alpha);
        }

        private void set(double nb0, double nb1, double nb2, double a0, double na1, double na2) {
            b0 = (float) (nb0 / a0);
            b1 = (float) (nb1 / a0);
            b2 = (float) (nb2 / a0);
            a1 = (float) (na1 / a0);
            a2 = (float) (na2 / a0);
        }

        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
