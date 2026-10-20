// The USB userspace audio driver behind UsbIsoSink — the app's own path to a
// USB DAC, the way HiBy/UAPP do it: claim the UAC interfaces from usbfs (which
// detaches the kernel's snd-usb-audio), speak the USB Audio Class protocol
// ourselves (descriptor parsing, sample-rate control, isochronous OUT data,
// feedback IN), and stream PCM straight to the device. AudioFlinger, the OEM
// HAL and their deep-buffer/effect policies never see the stream.
//
// Deliberately no libusb: everything needed is the standard usbdevice_fs API on
// the fd UsbDeviceConnection hands us, so the driver is one small .so with no
// third-party code and no licensing questions.

#include <jni.h>

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <poll.h>
#include <unistd.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#define LOG_TAG "qplayer-uac"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// usbfs helpers
// ---------------------------------------------------------------------------

int usbControl(int fd, uint8_t requestType, uint8_t request, uint16_t value,
               uint16_t index, uint16_t length, void* data, uint32_t timeoutMs) {
    usbdevfs_ctrltransfer transfer;
    memset(&transfer, 0, sizeof(transfer));
    transfer.bRequestType = requestType;
    transfer.bRequest = request;
    transfer.wValue = value;
    transfer.wIndex = index;
    transfer.wLength = length;
    transfer.timeout = timeoutMs;
    transfer.data = data;
    return ioctl(fd, USBDEVFS_CONTROL, &transfer);
}

// bionic's auto-generated kernel header predates this ioctl; the number is the
// stable kernel ABI ('U', 27). It detaches whichever kernel driver holds an
// interface and claims it in one step — the unprivileged replacement for the
// old (root-only) DISCONNECT+CLAIM pair.
#ifndef USBDEVFS_DISCONNECTCLAIM
struct usbdevfs_disconnect_claim {
    unsigned int interface;
    unsigned int flags;
    char driver[256];
};
#define USBDEVFS_DISCONNECTCLAIM _IOR('U', 27, struct usbdevfs_disconnect_claim)
#endif

// One alternate setting of one AudioStreaming interface.
struct AltSetting {
    int interfaceNumber = -1;
    int alt = -1;
    int channels = 2;
    int subslotSize = 2;      // bytes per sample container
    int bitResolution = 16;   // meaningful bits
    int epAddress = 0;        // OUT isochronous data endpoint
    int epMaxPacket = 0;
    int epAttributes = 0;     // 0x09 adaptive / 0x11 asynchronous
    int epInterval = 1;
    int feedbackAddress = 0;  // IN isochronous feedback endpoint, 0 when none
    std::vector<uint32_t> rates;  // UAC1 only; UAC2 asks the clock source
};

// What open() found in the descriptors.
struct DeviceInfo {
    bool uac2 = false;
    int clockId = 0;            // UAC2 clock source entity
    int acInterface = -1;       // the AudioControl interface number
    int featureUnitId = 0;      // the first Feature Unit seen
    int volumeUnitId = 0;       // one whose bitmap declares a master volume
    int volumeChannel = 0;      // primary channel that actually answers
    int volumeChannelMask = 0;  // EVERY channel that answers: writes must hit all
    int volumeMin = 0;          // 1/256 dB, from that unit's GET_RANGE reply
    int volumeMax = 0;
    int volumeRes = 0;
    bool volumeKnown = false;
    std::vector<AltSetting> alts;
    std::vector<int> audioInterfaces;  // every class-1 interface, for claiming
    std::vector<int> hidInterfaces;    // class-3 interfaces: the shell buttons
};

bool readConfigDescriptor(int fd, std::vector<uint8_t>* out) {
    uint8_t header[9];
    if (usbControl(fd, 0x80, 0x06, 0x0200, 0, 9, header, 500) != 9) return false;
    int total = header[2] | (header[3] << 8);
    if (total < 9 || total > 4096) return false;
    out->assign(total, 0);
    return usbControl(fd, 0x80, 0x06, 0x0200, 0, total, out->data(), 500) == total;
}

bool parseConfigDescriptor(const std::vector<uint8_t>& config, DeviceInfo* info) {
    AltSetting current;
    bool inAlt = false;
    int interfaceClass = 0;
    int interfaceSubclass = 0;
    size_t pos = 0;
    while (pos + 2 <= config.size()) {
        uint8_t length = config[pos];
        uint8_t type = config[pos + 1];
        if (length < 2 || pos + length > config.size()) break;
        switch (type) {
            case 0x04: {  // standard interface descriptor
                if (inAlt && current.alt > 0 && current.epAddress != 0) {
                    info->alts.push_back(current);
                }
                int interfaceNumber = config[pos + 2];
                interfaceClass = config[pos + 5];
                interfaceSubclass = config[pos + 6];
                current = AltSetting();
                current.interfaceNumber = interfaceNumber;
                current.alt = config[pos + 3];
                inAlt = interfaceClass == 0x01 && interfaceSubclass == 0x02;
                if (interfaceClass == 0x01
                        && std::find(info->audioInterfaces.begin(),
                                     info->audioInterfaces.end(), interfaceNumber)
                                == info->audioInterfaces.end()) {
                    info->audioInterfaces.push_back(interfaceNumber);
                }
                if (interfaceClass == 0x03
                        && std::find(info->hidInterfaces.begin(),
                                     info->hidInterfaces.end(), interfaceNumber)
                                == info->hidInterfaces.end()) {
                    info->hidInterfaces.push_back(interfaceNumber);
                }
                if (interfaceClass == 0x01 && interfaceSubclass == 0x01) {
                    info->acInterface = interfaceNumber;
                }
                break;
            }
            case 0x24: {  // class-specific interface descriptor
                uint8_t subtype = config[pos + 2];
                if (interfaceClass != 0x01) break;
                if (interfaceSubclass == 0x01) {  // AudioControl
                    // The terminals and units on this interface are invisible from
                    // an app process (SELinux blocks the usual probes), and reading
                    // one of them wrong is the difference between a working
                    // hardware volume control and none at all, so the driver
                    // reports the raw bytes of what it actually found.
                    {
                        char bytes[3 * 14 + 1];
                        int shown = length < 14 ? length : 14;
                        for (int i = 0; i < shown; i++) {
                            snprintf(bytes + i * 3, 4, "%02x ", config[pos + i]);
                        }
                        bytes[shown * 3] = 0;
                        LOGI("  ac desc subtype=0x%02x len=%d: %s",
                             subtype, length, bytes);
                    }
                    if (subtype == 0x01) {  // header
                        info->uac2 = (config[pos + 3] | (config[pos + 4] << 8)) >= 0x0200;
                    } else if (subtype == 0x0A && info->uac2) {  // clock source
                        // First clock source wins; a DAC normally has one.
                        int id = config[pos + 3];
                        int controls = length > 5 ? config[pos + 5] : 0;
                        if (info->clockId == 0 && (controls & 0x03) != 0) {
                            info->clockId = id;
                        }
                    } else if (subtype == 0x06) {  // feature unit
                        // bUnitID(3) bSourceID(4) then the bmaControls entries,
                        // master channel first. Bit 1 is the volume control. UAC2
                        // always uses 4-byte entries and has no bControlSize byte;
                        // UAC1 interposes it at offset 5.
                        uint32_t master = 0;
                        if (info->uac2 && length >= 9) {
                            for (int i = 0; i < 4; i++) {
                                master |= (uint32_t) config[pos + 5 + i] << (8 * i);
                            }
                        } else if (!info->uac2 && length >= 7) {
                            int size = config[pos + 5];
                            for (int i = 0; i < size && i < 4
                                    && pos + 6 + i < config.size(); i++) {
                                master |= (uint32_t) config[pos + 6 + i] << (8 * i);
                            }
                        }
                        // Record the unit whatever its bitmap says. This DAC
                        // advertises no volume bit at all yet still answers volume
                        // requests — another player controls it that way — so the
                        // decision belongs to the probe in queryVolumeRange(), not
                        // to the bitmap. A unit that does declare the bit wins.
                        if (info->featureUnitId == 0) {
                            info->featureUnitId = config[pos + 3];
                        }
                        if (info->volumeUnitId == 0 && (master & 0x02) != 0) {
                            info->volumeUnitId = config[pos + 3];
                        }
                    }
                } else if (interfaceSubclass == 0x02 && inAlt) {  // AudioStreaming
                    if (subtype == 0x01) {  // AS_GENERAL
                        // UAC2 layout: bTerminalLink(3) bmControls(4) bFormatType(5)
                        // bmFormats(6..9) bNrChannels(10). UAC1 has no channel
                        // count here — it lives in FORMAT_TYPE below.
                        if (info->uac2 && length >= 11) {
                            current.channels = config[pos + 10];
                        }
                    } else if (subtype == 0x02) {  // FORMAT_TYPE
                        if (info->uac2) {
                            // UAC2: bFormatType(3) bSubslotSize(4) bBitResolution(5).
                            current.subslotSize = config[pos + 4];
                            current.bitResolution = config[pos + 5];
                        } else {
                            // UAC1 type I: bFormatType(3) bNrChannels(4)
                            // bSubframeSize(5) bBitResolution(6) tSamFreq(7..).
                            current.channels = config[pos + 4];
                            current.subslotSize = config[pos + 5];
                            current.bitResolution = config[pos + 6];
                            int freqs = (length - 7) / 3;
                            for (int i = 0; i < freqs; i++) {
                                size_t at = pos + 7 + (size_t) i * 3;
                                uint32_t rate = config[at]
                                        | (config[at + 1] << 8)
                                        | (config[at + 2] << 16);
                                if (rate > 0) current.rates.push_back(rate);
                            }
                        }
                    }
                }
                break;
            }
            case 0x05: {  // standard endpoint descriptor
                if (!inAlt) break;
                uint8_t address = config[pos + 2];
                uint8_t attributes = config[pos + 3];
                int maxPacket = config[pos + 4] | (config[pos + 5] << 8);
                int interval = config[pos + 6];
                if ((attributes & 0x03) == 0x01) {  // isochronous
                    if ((address & 0x80) == 0) {  // OUT = the data endpoint
                        current.epAddress = address;
                        current.epAttributes = attributes;
                        current.epMaxPacket = maxPacket & 0x07ff;
                        current.epInterval = std::max(1, interval);
                    } else if (current.feedbackAddress == 0) {
                        current.feedbackAddress = address;
                    }
                }
                break;
            }
            default:
                break;
        }
        pos += length;
    }
    if (inAlt && current.alt > 0 && current.epAddress != 0) {
        info->alts.push_back(current);
    }
    return !info->alts.empty();
}

// ---------------------------------------------------------------------------
// The stream: descriptor state + a ring buffer + one usbfs submit/reap thread.
// ---------------------------------------------------------------------------

// A usbfs isochronous URB is a flex array: the kernel reads and writes the
// packet descriptors from/to `user_urb + sizeof(usbdevfs_urb)`, contiguous in
// the SAME allocation. A usbdevfs_urb member followed by anything else would
// have the descriptors trample that member (and have the kernel read garbage),
// so each URB owns one block: [usbdevfs_urb][packet descriptors...].
namespace urb_layout {

inline usbdevfs_urb* urb(std::vector<uint8_t>& block) {
    return reinterpret_cast<usbdevfs_urb*>(block.data());
}

inline usbdevfs_iso_packet_desc* desc(std::vector<uint8_t>& block, int index) {
    return reinterpret_cast<usbdevfs_iso_packet_desc*>(
            block.data() + sizeof(usbdevfs_urb)) + index;
}

inline size_t isocBlockBytes(int packets) {
    return sizeof(usbdevfs_urb)
            + (size_t) packets * sizeof(usbdevfs_iso_packet_desc);
}

}  // namespace urb_layout

struct IsoUrb {
    std::vector<uint8_t> block;   // usbdevfs_urb + packetsPerUrb_ descriptors
    std::vector<uint8_t> buffer;  // the isoc payload itself
    bool submitted = false;
    int64_t dataBytes = 0;  // real (non-silence) payload queued in this URB
};

struct FeedbackUrb {
    std::vector<uint8_t> block;   // usbdevfs_urb + one descriptor
    std::vector<uint8_t> buffer;
    bool submitted = false;
};

class UacStream {
public:
    ~UacStream() { teardown(); }

    // Error tokens are translated to localized text on the Java side.
    const std::string& lastError() const { return lastError_; }

    bool open(int fd, int requestedRate, int channels, bool floatPcm, int preferDepth);
    void start();
    void pause();
    void flush();
    void setVolume(float volume);   // 0..1, onto the DAC's own volume control
    int write(const uint8_t* src, int bytes);  // blocking; returns bytes consumed
    void close();   // release claims and tear the stream down
    void teardown();

    bool exclusive() const { return opened_; }
    int sampleRate() const { return opened_ ? rate_ : 0; }
    int channels() const { return opened_ ? channels_ : 0; }
    int bitDepth() const { return opened_ ? bitResolution_ : 0; }
    bool floatPcm() const { return opened_ && floatPcm_; }
    bool hasHardwareVolume() const { return opened_ && info_.volumeKnown; }
    void setHardwareVolume(float volume);  // 0..1 onto the DAC's own control
    void setSoftwareVolume(float volume);  // 0..1 as a digital gain
    void probeVolume();                    // diagnostic sweep + readbacks
    int64_t deviceVolumeRaw();             // GET_CUR readback, INT16_MIN when n/a
    bool writeVolumeRawAll(int16_t raw);   // one value onto every live channel
    int64_t framesQueued();  // ring + in-flight, in frames

private:
    void setError(const char* token) { lastError_ = token; }
    bool claimInterfaces();
    bool chooseAlt(int channels, int preferDepth, int requestedRate);
    bool setSampleRate(uint32_t rate);
    bool queryVolumeRange();
    void stopStreaming();
    void feedLoop();
    void submitFeedback();
    void refillUrb(IsoUrb& urb);  // caller holds mutex_
    int reapUrb(int timeoutMs);   // returns how many URBs were completed
    int64_t ringCapacityBytes() const {
        // ~0.8 s. Deep enough that a mid-track source switch (the online-cache
        // file handoff, whose decoder rebuild costs a few hundred milliseconds)
        // is bridged entirely by buffered audio; the seek/flush paths reset it,
        // so latency never accumulates.
        return std::max<int64_t>(16384, (int64_t) rate_ * frameBytesDac_ * 8 / 10);
    }

    int fd_ = -1;
    bool opened_ = false;
    bool streaming_ = false;
    DeviceInfo info_;
    AltSetting alt_;
    bool uac2_ = false;
    int rate_ = 0;
    int channels_ = 2;
    int bitResolution_ = 16;
    int subslotSize_ = 2;
    bool floatPcm_ = false;
    float softwareGain_ = 1.0f;  // sample scaling; only with no hardware volume
    int64_t frameBytesDac_ = 4;
    int64_t intervalUs_ = 125;
    bool highSpeed_ = true;
    int packetsPerUrb_ = 32;
    int urbCount_ = 4;         // URBs kept in flight; ≈128 ms of audio
    int64_t packetAcc_ = 0;    // pacing remainder (µs domain)
    int64_t feedbackRate_ = 0;
    int initialVolumeRaw_ = -32769;  // DAC level at open (1/256 dB); < -32768 = unread

    // Observability only (no effect on the stream). A silently-starved driver
    // looks identical to a healthy one from Java, because the device keeps
    // receiving packets — just all-zero ones. These separate the two possible
    // causes: the decode side stopped feeding, or the device stopped consuming.
    int64_t totalWrittenBytes_ = 0;   // caller bytes accepted into the ring
    int64_t starvedUrbs_ = 0;         // URBs submitted with an empty ring
    int64_t flushCount_ = 0;          // flush() calls: a storm here drains the ring
    int64_t flushedBytes_ = 0;        // real audio discarded by flush()
    // Payload audit: a single zero-filled buffer is a legitimate transient
    // (fade, underrun), so only a *continuously* constant payload is reported.
    bool payloadLive_ = false;
    int64_t payloadConstantSinceMs_ = -1;
    int64_t payloadStuckSinceMs_ = -1;

    std::vector<IsoUrb> urbs_;
    std::vector<FeedbackUrb> feedbackUrbs_;

    std::vector<uint8_t> ring_;
    size_t ringRead_ = 0;
    size_t ringWrite_ = 0;
    int64_t ringBytes_ = 0;       // real data queued, not counting silence pad
    int64_t inFlightBytes_ = 0;

    std::thread feeder_;
    mutable std::mutex mutex_;
    std::condition_variable spaceAvailable_;
    std::condition_variable dataAvailable_;
    std::atomic<bool> running_{false};

    std::string lastError_;
};

bool UacStream::open(int fd, int requestedRate, int channels, bool floatPcm,
                     int preferDepth) {
    teardown();
    fd_ = fd;
    if (fd < 0) { setError("err:fd"); return false; }
    if (requestedRate <= 0) { setError("err:rate"); return false; }

    std::vector<uint8_t> config;
    if (!readConfigDescriptor(fd_, &config)) { setError("err:desc"); return false; }
    if (!parseConfigDescriptor(config, &info_)) { setError("err:noaudio"); return false; }
    uac2_ = info_.uac2;

    // The descriptors are otherwise invisible from an app process (SELinux
    // blocks /proc/asound), so the driver reports what it found itself.
    LOGI("parsed uac%d: %zu alt(s), ac iface=%d clock=%d",
         uac2_ ? 2 : 1, info_.alts.size(), info_.acInterface, info_.clockId);
    for (const AltSetting& alt : info_.alts) {
        LOGI("  alt iface=%d alt=%d ch=%d subslot=%d bits=%d ep=%02x maxpkt=%d",
             alt.interfaceNumber, alt.alt, alt.channels, alt.subslotSize,
             alt.bitResolution, alt.epAddress, alt.epMaxPacket);
    }

    if (!claimInterfaces()) return false;  // claimInterfaces sets the token
    if (!chooseAlt(channels, preferDepth, requestedRate)) return false;

    if (!setSampleRate((uint32_t) requestedRate)) { setError("err:rate"); return false; }

    // An exclusive stream has no software gain, so the only way the app's volume
    // can reach the listener is through the DAC's own control.
    queryVolumeRange();

    usbdevfs_setinterface set;
    memset(&set, 0, sizeof(set));
    set.interface = (unsigned int) alt_.interfaceNumber;
    set.altsetting = alt_.alt;
    if (ioctl(fd_, USBDEVFS_SETINTERFACE, &set) != 0) {
        LOGW("set interface %d alt %d failed: %s",
             alt_.interfaceNumber, alt_.alt, strerror(errno));
        setError("err:alt");
        return false;
    }

    rate_ = requestedRate;
    channels_ = channels;
    bitResolution_ = alt_.bitResolution;
    subslotSize_ = alt_.subslotSize;
    floatPcm_ = floatPcm;
    frameBytesDac_ = (int64_t) channels_ * subslotSize_;
    // UAC2 implies high speed; a UAC1 device old enough to be full speed
    // reports a low bcdUSB. (High-speed UAC1 DACs exist but are rare.)
    highSpeed_ = uac2_;
    intervalUs_ = highSpeed_ ? (int64_t) 125 << (alt_.epInterval - 1)
                             : (int64_t) alt_.epInterval * 1000;
    // ~4 ms of audio per URB (4000 µs, not per-packet): 125 µs endpoints come
    // out at 32 packets, slow (1–2 ms) endpoints at the 16-packet floor. The
    // numbers match the reference implementation this driver was modelled on.
    int64_t target = 4000 / intervalUs_;
    packetsPerUrb_ = (int) std::min<int64_t>(32, std::max<int64_t>(16, target));
    // Total audio queued on the wire, the real defence against a stuttering
    // DAC. Only a handful of URBs would leave ~12 ms in flight, which a single
    // Android scheduling hiccup consumes at once — the device then gets an
    // all-zero packet and the music audibly drops out. Aim for ~128 ms, the
    // depth the reference implementation settled on: URB count is derived from
    // how much each one holds so the target holds for any endpoint interval.
    int64_t perUrbUs = (int64_t) packetsPerUrb_ * intervalUs_;
    urbCount_ = (int) std::min<int64_t>(32, std::max<int64_t>(4, 128000 / perUrbUs));

    ring_.assign((size_t) ringCapacityBytes(), 0);
    ringRead_ = ringWrite_ = 0;
    ringBytes_ = inFlightBytes_ = 0;
    packetAcc_ = 0;
    feedbackRate_ = 0;
    opened_ = true;
    // The level the DAC's buttons left it at, read BEFORE any isochronous
    // traffic: once the stream runs this device refuses GET_CUR outright. The
    // app adopts this level instead of stomping it, so switching to exclusive
    // never changes loudness by itself.
    initialVolumeRaw_ = -32769;
    if (info_.volumeKnown) {
        uint16_t vi = ((uint16_t) (info_.volumeUnitId != 0 ? info_.volumeUnitId
                                                           : info_.featureUnitId)
                       << 8) | (uint16_t) info_.acInterface;
        uint8_t g[2] = {0, 0};
        int n = usbControl(fd_, 0xA1, 0x01,
                           (uint16_t) ((0x02 << 8) | info_.volumeChannel),
                           vi, 2, g, 500);
        if (n == 2) initialVolumeRaw_ = (int16_t) (g[0] | (g[1] << 8));
        LOGI("device volume at open: %.1f dB", initialVolumeRaw_ / 256.0f);
    }
    LOGI("opened uac%s: iface=%d alt=%d rate=%d ch=%d bits=%d subslot=%d "
         "interval=%lldus packets/urb=%d urbs=%d (≈%lldms in flight) feedback=0x%02x",
         uac2_ ? "2" : "1", alt_.interfaceNumber, alt_.alt, rate_, channels_,
         bitResolution_, subslotSize_, (long long) intervalUs_, packetsPerUrb_,
         urbCount_, (long long) ((int64_t) urbCount_ * packetsPerUrb_ * intervalUs_ / 1000),
         alt_.feedbackAddress);
    return true;
}

bool UacStream::claimInterfaces() {
    for (int iface : info_.audioInterfaces) {
        usbdevfs_disconnect_claim claim;
        memset(&claim, 0, sizeof(claim));
        claim.interface = (unsigned int) iface;
        claim.flags = 0;  // detach whichever kernel driver holds it
        if (ioctl(fd_, USBDEVFS_DISCONNECTCLAIM, &claim) == 0) continue;
        int saved = errno;
        if (saved == ENOTTY) {
            // Kernels without DISCONNECTCLAIM fall back to a plain claim; on
            // EBUSY the kernel driver is attached and a plain disconnect needs
            // privileges we do not have — that is the "busy" the card shows.
            unsigned int number = (unsigned int) iface;
            if (ioctl(fd_, USBDEVFS_CLAIMINTERFACE, &number) == 0) continue;
            saved = errno;
        }
        LOGW("claim interface %d failed: %s", iface, strerror(saved));
        setError(saved == EBUSY ? "err:busy" : "err:claim");
        return false;
    }
    return true;
}

// UAC2 rate support from the clock source's GET_RANGE reply. Control transfers
// need no claim, so this runs during alt selection.
bool uac2RateSupported(int fd, const DeviceInfo& info, uint32_t rate) {
    if (info.clockId == 0) return rate == 48000 || rate == 44100;  // no clock found
    uint8_t reply[128];
    memset(reply, 0, sizeof(reply));
    uint16_t value = (0x01 << 8);  // SAM_FREQ_CONTROL, channel 0
    uint16_t index = ((uint16_t) info.clockId << 8) | (uint16_t) info.acInterface;
    int n = usbControl(fd, 0xA1, 0x02, value, index, sizeof(reply), reply, 500);
    if (n < 2) return false;
    int subRanges = reply[0] | (reply[1] << 8);
    for (int i = 0; i < subRanges; i++) {
        size_t at = 2 + (size_t) i * 9;
        if (at + 9 > (size_t) n) break;
        uint32_t min = reply[at] | (reply[at + 1] << 8)
                | ((uint32_t) reply[at + 2] << 16) | ((uint32_t) reply[at + 3] << 24);
        uint32_t max = reply[at + 4] | (reply[at + 5] << 8)
                | ((uint32_t) reply[at + 6] << 16) | ((uint32_t) reply[at + 7] << 24);
        uint8_t res = reply[at + 8];
        if (rate < min || rate > max) continue;
        if (res == 0 || res == 1 || rate % res == 0) return true;
    }
    return false;
}

bool UacStream::chooseAlt(int channels, int preferDepth, int requestedRate) {
    const AltSetting* best = nullptr;
    int bestScore = -1;
    for (const AltSetting& alt : info_.alts) {
        if (alt.channels != channels) continue;
        if (alt.subslotSize < 2 || alt.subslotSize > 4) continue;
        if (alt.bitResolution < 16 || alt.bitResolution > 32) continue;
        if (uac2_) {
            if (!uac2RateSupported(fd_, info_, (uint32_t) requestedRate)) continue;
        } else {
            bool listed = false;
            for (uint32_t r : alt.rates) {
                if ((int) r == requestedRate) { listed = true; break; }
            }
            if (!listed) continue;
        }
        // Prefer the DAC depth matching the source; otherwise the smallest
        // depth that fits it (16-bit into 24/32 pads toward the top); finally
        // the largest depth (hi-res kept in full).
        int score;
        if (alt.bitResolution == preferDepth) {
            score = 100000;
        } else if (alt.bitResolution >= preferDepth) {
            score = 50000 - alt.bitResolution;
        } else {
            score = 10000 + alt.bitResolution;
        }
        score -= alt.subslotSize;  // tie-break: tighter container
        if (score > bestScore) {
            bestScore = score;
            best = &alt;
        }
    }
    if (best == nullptr) {
        LOGW("no alt setting for rate=%d ch=%d depth=%d",
             requestedRate, channels, preferDepth);
        setError("err:rate");
        return false;
    }
    alt_ = *best;
    return true;
}

bool UacStream::setSampleRate(uint32_t rate) {
    if (uac2_) {
        uint32_t value = rate;  // little-endian on the wire
        uint16_t wValue = (0x01 << 8);  // SAM_FREQ_CONTROL, channel 0
        uint16_t wIndex = ((uint16_t) info_.clockId << 8) | (uint16_t) info_.acInterface;
        if (usbControl(fd_, 0x21, 0x01, wValue, wIndex, 4, &value, 500) != 4) {
            LOGW("uac2 set rate %u failed: %s", rate, strerror(errno));
            return false;
        }
        return true;
    }
    // UAC1: the request goes to the data endpoint, 24-bit LE frequency.
    uint8_t value[3] = {
        (uint8_t) (rate & 0xFF),
        (uint8_t) ((rate >> 8) & 0xFF),
        (uint8_t) ((rate >> 16) & 0xFF),
    };
    uint16_t wValue = (0x01 << 8);  // SAMPLING_FREQ_CONTROL
    if (usbControl(fd_, 0x22, 0x01, wValue, (uint16_t) alt_.epAddress, 3, value, 500) != 3) {
        LOGW("uac1 set rate %u failed: %s", rate, strerror(errno));
        return false;
    }
    return true;
}

// Ask the Feature Unit for its volume range. Without this the app's volume
// slider would do nothing at all on an exclusive stream: the samples leave
// unaltered, so loudness is whatever the DAC's own control says, and it defaults
// to full scale.
bool UacStream::queryVolumeRange() {
    int unit = info_.volumeUnitId != 0 ? info_.volumeUnitId : info_.featureUnitId;
    if (!uac2_ || unit == 0) {
        LOGI("no Feature Unit to hold a hardware volume control; the app volume will scale samples");
        return false;
    }
    uint16_t wIndex = ((uint16_t) unit << 8) | (uint16_t) info_.acInterface;
    // Ask rather than believe, and ask EVERY channel: this DAC's Feature Unit
    // declares no volume bit at all, yet it answers volume requests on the
    // master AND both stereo channels — and it only truly applies the
    // per-channel ones. A master-only write moved the left ear and left the
    // right ear at full scale, so the write mask is every channel that answers.
    int mask = 0;
    int primary = -1;
    int lo = 0, hi = 0, res = 0;
    for (int channel = 0; channel < 3; channel++) {
        uint8_t reply[16];
        memset(reply, 0, sizeof(reply));
        int n = usbControl(fd_, 0xA1, 0x02, (uint16_t) ((0x02 << 8) | channel),
                           wIndex, sizeof(reply), reply, 500);   // GET_RANGE
        if (n >= 8 && (reply[0] | (reply[1] << 8)) >= 1) {
            int rlo = (int16_t) (reply[2] | (reply[3] << 8));
            int rhi = (int16_t) (reply[4] | (reply[5] << 8));
            if (rhi > rlo) {
                mask |= 1 << channel;
                if (primary < 0) {
                    primary = channel;
                    lo = rlo;
                    hi = rhi;
                    res = (int16_t) (reply[6] | (reply[7] << 8));
                }
            }
        }
    }
    if (mask != 0) {
        info_.volumeMin = lo;
        info_.volumeMax = hi;
        info_.volumeRes = res;
        info_.volumeKnown = true;
    } else {
        // No range anywhere; a GET_CUR answer still proves a control exists. Its
        // real range is unknown, so use the class default shape: 0 dB at the top
        // and -60 dB comfortably inside whatever the device really holds.
        for (int channel = 0; channel < 3; channel++) {
            uint8_t reply[2] = {0, 0};
            int n = usbControl(fd_, 0xA1, 0x01, (uint16_t) ((0x02 << 8) | channel),
                               wIndex, 2, reply, 500);   // GET_CUR
            if (n >= 2) {
                mask |= 1 << channel;
                if (primary < 0) primary = channel;
            }
        }
        if (mask != 0) {
            info_.volumeMin = -60 * 256;
            info_.volumeMax = 0;
            info_.volumeRes = 1;
            info_.volumeKnown = true;
        }
    }
    if (!info_.volumeKnown) {
        LOGI("the DAC answers no volume request; the app volume will scale samples");
        return false;
    }
    info_.volumeChannelMask = mask;
    info_.volumeChannel = primary;
    LOGI("volume control: unit=%d channels=0x%x (primary=%d) range=%d..%d (1/256 dB) res=%d",
         unit, mask, primary, info_.volumeMin, info_.volumeMax, info_.volumeRes);
    return true;
}

// One raw volume value onto every channel the device answered for. DACs that
// only implement per-channel controls (this one among them) go off balance if
// any responding channel is skipped.
bool UacStream::writeVolumeRawAll(int16_t raw) {
    int unit = info_.volumeUnitId != 0 ? info_.volumeUnitId : info_.featureUnitId;
    uint16_t wIndex = ((uint16_t) unit << 8) | (uint16_t) info_.acInterface;
    uint8_t value[2] = {
        (uint8_t) (raw & 0xFF),
        (uint8_t) ((raw >> 8) & 0xFF),
    };
    bool ok = true;
    for (int channel = 0; channel < 3; channel++) {
        if (!(info_.volumeChannelMask & (1 << channel))) continue;
        uint16_t wValue = (uint16_t) ((0x02 << 8) | channel);
        // This DAC's control endpoint drops roughly one request in three while
        // the isochronous stream is running; one silent retry recovers those.
        int w = usbControl(fd_, 0x21, 0x01, wValue, wIndex, 2, value, 500);
        if (w != 2) {
            usleep(40 * 1000);
            w = usbControl(fd_, 0x21, 0x01, wValue, wIndex, 2, value, 500);
        }
        if (w != 2) {
            LOGW("volume write ch=%d failed: %s", channel, strerror(errno));
            ok = false;
        }
    }
    return ok;
}

void UacStream::setHardwareVolume(float volume) {
    if (!opened_ || !info_.volumeKnown) return;
    float minDb = info_.volumeMin / 256.0f;
    float maxDb = info_.volumeMax / 256.0f;
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    // 0..1 maps across the *audible* part of the range, not all of it: a real
    // DAC often reaches about -127 dB, where every step is inaudible, so
    // attenuating linearly over the whole range would put nearly all of the
    // loudness in the top two steps. Anchor the bottom at -50 dB.
    float floorDb = std::max(minDb, -50.0f);
    float db = floorDb + (maxDb - floorDb) * volume;
    if (db < minDb) db = minDb;
    if (db > maxDb) db = maxDb;
    int16_t raw = (int16_t) llround(db * 256.0f);
    // The -128 dB code point means silence in the UAC spec; never ask for it by
    // accident, or a track at the slider's bottom would mute outright.
    if (raw == (int16_t) 0x8000) raw++;
    if (!writeVolumeRawAll(raw)) {
        LOGW("volume set %.1f dB incomplete", db);
    }
}

void UacStream::setSoftwareVolume(float volume) {
    std::lock_guard<std::mutex> guard(mutex_);
    softwareGain_ = volume;
}

// The DAC's own idea of its current volume (raw 1/256 dB units), or the most
// negative int16 when the device answers nothing. Diagnostic: beats log this so
// the DAC's own buttons show up in logcat — whether they write the same control
// we do, and at what step, is exactly what the "mutes below a moving threshold"
// report hinges on.
int64_t UacStream::deviceVolumeRaw() {
    if (!opened_ || !info_.volumeKnown) return INT32_MIN;
    int unit = info_.volumeUnitId != 0 ? info_.volumeUnitId : info_.featureUnitId;
    uint16_t wIndex = ((uint16_t) unit << 8) | (uint16_t) info_.acInterface;
    uint8_t g[2] = {0, 0};
    int n = usbControl(fd_, 0xA1, 0x01, (uint16_t) ((0x02 << 8) | info_.volumeChannel),
                       wIndex, 2, g, 200);
    if (n != 2) return INT32_MIN;
    return (int16_t) (g[0] | (g[1] << 8));
}

// One-shot diagnostic, run from the debug broadcast: re-asks every channel's
// range, then writes a descending ladder of volumes with a readback after each,
// so the log shows exactly where the device clamps, stalls, or answers mute.
void UacStream::probeVolume() {
    if (!opened_) { LOGI("volprobe: stream not open"); return; }
    int unit = info_.volumeUnitId != 0 ? info_.volumeUnitId : info_.featureUnitId;
    if (unit == 0) { LOGI("volprobe: no feature unit"); return; }
    uint16_t wIndex = ((uint16_t) unit << 8) | (uint16_t) info_.acInterface;
    LOGI("volprobe: unit=%d acIface=%d known=%d ch=%d stored range=%d..%d res=%d",
         unit, info_.acInterface, info_.volumeKnown ? 1 : 0, info_.volumeChannel,
         info_.volumeMin, info_.volumeMax, info_.volumeRes);
    for (int ch = 0; ch < 3; ch++) {
        uint8_t r[16];
        memset(r, 0, sizeof(r));
        int n = usbControl(fd_, 0xA1, 0x02, (uint16_t) ((0x02 << 8) | ch),
                           wIndex, sizeof(r), r, 500);
        if (n >= 8) {
            LOGI("volprobe: GET_RANGE ch=%d subranges=%d min=%d max=%d res=%d",
                 ch, r[0] | (r[1] << 8), (int16_t) (r[2] | (r[3] << 8)),
                 (int16_t) (r[4] | (r[5] << 8)), (int16_t) (r[6] | (r[7] << 8)));
        } else {
            LOGI("volprobe: GET_RANGE ch=%d -> %d bytes (refused)", ch, n);
        }
    }
    static const int16_t ladder[] = {0, -1280, -2560, -5120, -7680,
                                     -10240, -12800, -15360};
    for (int i = 0; i < (int) (sizeof(ladder) / sizeof(ladder[0])); i++) {
        int16_t raw = ladder[i];
        bool w = writeVolumeRawAll(raw);
        int64_t back = deviceVolumeRaw();
        LOGI("volprobe: set %.1f dB (w=%d) -> device now %.1f dB",
             raw / 256.0, w ? 2 : -1, back == INT32_MIN ? -999.0 : back / 256.0);
        usleep(500 * 1000);  // audible gap so each step can be heard live
    }
    writeVolumeRawAll(0);
    LOGI("volprobe: restored 0 dB");

    // The shell buttons almost certainly live on a HID interface (the device
    // exposes one alongside its audio interfaces). The report descriptor names
    // the usages the buttons emit — and, if the device reports its own state,
    // gives us a way to sync the app with the buttons without guesswork.
    for (int hid : info_.hidInterfaces) {
        LOGI("hid iface=%d: dumping report descriptor", hid);
        uint8_t r[255];
        memset(r, 0, sizeof(r));
        int n = usbControl(fd_, 0x81, 0x06, 0x2200, (uint16_t) hid,
                           sizeof(r), r, 500);
        if (n <= 0) {
            LOGI("hid iface=%d: report descriptor refused (%d)", hid, n);
            continue;
        }
        char line[90];
        for (int off = 0; off < n; off += 24) {
            int shown = std::min(24, n - off);
            for (int i = 0; i < shown; i++) {
                snprintf(line + i * 3, 4, "%02x ", r[off + i]);
            }
            line[shown * 3] = 0;
            LOGI("hid iface=%d desc %d/%d: %s", hid, off + shown, n, line);
        }
        // A GET_REPORT attempt: some devices answer with their live state
        // (button bitmap / volume position), which is exactly the sync source
        // we are looking for.
        uint8_t st[8];
        memset(st, 0, sizeof(st));
        int sn = usbControl(fd_, 0x81, 0x01, 0x0100, (uint16_t) hid,
                            sizeof(st), st, 500);   // GET_REPORT, input, id 0
        if (sn > 0) {
            char line2[3 * 8 + 1];
            int shown = sn < 8 ? sn : 8;
            for (int i = 0; i < shown; i++) {
                snprintf(line2 + i * 3, 4, "%02x ", st[i]);
            }
            line2[shown * 3] = 0;
            LOGI("hid iface=%d GET_REPORT(%d bytes): %s", hid, sn, line2);
        } else {
            LOGI("hid iface=%d GET_REPORT refused (%d)", hid, sn);
        }
    }
    if (info_.hidInterfaces.empty()) {
        LOGI("hid: no HID interface in the current configuration");
    }
}

void UacStream::setVolume(float volume) {
    if (!opened_) return;
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    if (!info_.volumeKnown) {
        // Nothing to write the level into, so the only way it can change at all is
        // to scale the samples on their way into the ring. That gives up the
        // bit-perfect property, so it is used only here, where there is no
        // alternative; a DAC with its own control keeps its samples intact. At
        // full scale the samples are left alone, so bit-perfect is still reachable.
        setSoftwareVolume(volume);
        return;
    }
    setHardwareVolume(volume);
}

void UacStream::start() {
    if (!opened_ || streaming_) return;
    streaming_ = true;
    running_ = true;
    urbs_.assign((size_t) urbCount_, IsoUrb());
    for (IsoUrb& urb : urbs_) {
        urb.block.assign(urb_layout::isocBlockBytes(packetsPerUrb_), 0);
    }
    feedbackUrbs_.clear();
    feeder_ = std::thread(&UacStream::feedLoop, this);
}

void UacStream::pause() {
    if (!streaming_) return;
    stopStreaming();
}

void UacStream::flush() {
    std::lock_guard<std::mutex> guard(mutex_);
    flushCount_++;
    flushedBytes_ += ringBytes_;
    LOGI("flush: discarding ring=%lld inflight=%lld (flush #%lld)",
         (long long) ringBytes_, (long long) inFlightBytes_, (long long) flushCount_);
    ringRead_ = ringWrite_ = 0;
    ringBytes_ = 0;
    // In-flight URBs still carry their payload; discard them so the device does
    // not keep playing the old position. The reaper drops their byte counts.
    for (IsoUrb& urb : urbs_) {
        if (urb.submitted) ioctl(fd_, USBDEVFS_DISCARDURB, urb_layout::urb(urb.block));
    }
    packetAcc_ = 0;
    spaceAvailable_.notify_all();
}

int UacStream::write(const uint8_t* src, int bytes) {
    if (!opened_ || !streaming_) return 0;
    // Convert into the DAC's container on the way into the ring; a 16-bit
    // source on a 16-bit alt setting is a plain passthrough.
    int srcFrameBytes = floatPcm_ ? channels_ * 4 : channels_ * 2;
    if (srcFrameBytes <= 0 || bytes < srcFrameBytes) return 0;
    int srcFrames = bytes / srcFrameBytes;
    int toWrite = srcFrames * srcFrameBytes;
    int written = 0;
    const int kChunkFrames = 256;
    std::vector<uint8_t> chunk((size_t) kChunkFrames * channels_ * 4);
    std::unique_lock<std::mutex> guard(mutex_);
    while (written < toWrite) {
        if (!opened_ || !streaming_) return written;
        int frames = std::min(kChunkFrames, (toWrite - written) / srcFrameBytes);
        int srcBytes = frames * srcFrameBytes;
        int outBytes = frames * (int) frameBytesDac_;
        if (ringBytes_ + outBytes > (int64_t) ring_.size()) {
            if (spaceAvailable_.wait_for(guard, std::chrono::milliseconds(100))
                    == std::cv_status::timeout && (!opened_ || !streaming_)) {
                return written;
            }
            continue;
        }
        const uint8_t* in = src + written;
        uint8_t* out = chunk.data();
        int containerShift = subslotSize_ * 8 - bitResolution_;
        // Read once per chunk: the lock is held, and the gain only changes on a
        // volume press. At 1.0 nothing is touched, so a richer path stays exact.
        const float gain = softwareGain_;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels_; c++) {
                int64_t value;
                if (floatPcm_) {
                    double x = ((const float*) (const void*) in)[f * channels_ + c];
                    if (x > 1.0) x = 1.0;
                    if (x < -1.0) x = -1.0;
                    // double math: float precision is not enough for 32-bit.
                    value = (int64_t) llround(
                            x * (double) ((1LL << (bitResolution_ - 1)) - 1));
                    value <<= containerShift;
                } else {
                    int16_t sample = ((const int16_t*) (const void*) in)[f * channels_ + c];
                    if (bitResolution_ > 16) {
                        // 16-bit sample into the top of the DAC's resolution.
                        value = (int64_t) sample << (bitResolution_ - 16);
                        value <<= containerShift;
                    } else {
                        value = sample;
                    }
                }
                if (gain != 1.0f) {
                    value = (int64_t) llround((double) value * gain);
                }
                uint8_t* at = out + (size_t) ((f * channels_ + c) * subslotSize_);
                for (int b = 0; b < subslotSize_; b++) {
                    at[b] = (uint8_t) ((value >> (8 * b)) & 0xFF);
                }
            }
        }
        int offset = 0;
        while (offset < outBytes) {
            size_t tail = ring_.size() - ringWrite_;
            size_t take = std::min(tail, (size_t) (outBytes - offset));
            memcpy(ring_.data() + ringWrite_, chunk.data() + offset, take);
            ringWrite_ = (ringWrite_ + take) % ring_.size();
            offset += (int) take;
        }
        ringBytes_ += outBytes;
        totalWrittenBytes_ += outBytes;
        written += srcBytes;
        dataAvailable_.notify_all();
    }
    return written;
}

int64_t UacStream::framesQueued() {
    std::lock_guard<std::mutex> guard(mutex_);
    return (ringBytes_ + inFlightBytes_) / frameBytesDac_;
}

void UacStream::stopStreaming() {
    LOGI("stream stop: ring=%lld inflight=%lld",
         (long long) ringBytes_, (long long) inFlightBytes_);
    // Stop the flag AND wake any writer waiting for ring space before waiting on
    // the feeder. A writer parked in write() with a full ring is released by a
    // notification and by nothing else, and once the feeder is gone none is
    // coming: it would sit there for good, the sink would never be reopened, and
    // the next track would never play.
    {
        std::lock_guard<std::mutex> guard(mutex_);
        streaming_ = false;
    }
    spaceAvailable_.notify_all();
    if (feeder_.joinable()) {
        running_ = false;
        dataAvailable_.notify_all();
        feeder_.join();
    }
    spaceAvailable_.notify_all();
    for (IsoUrb& urb : urbs_) {
        if (urb.submitted) {
            ioctl(fd_, USBDEVFS_DISCARDURB, urb_layout::urb(urb.block));
            urb.submitted = false;
        }
        urb.dataBytes = 0;
    }
    for (FeedbackUrb& urb : feedbackUrbs_) {
        if (urb.submitted) {
            ioctl(fd_, USBDEVFS_DISCARDURB, urb_layout::urb(urb.block));
            urb.submitted = false;
        }
    }
    inFlightBytes_ = 0;
}

void UacStream::submitFeedback() {
    if (alt_.feedbackAddress == 0) return;
    while (feedbackUrbs_.size() < 2) {
        FeedbackUrb urb;
        urb.block.assign(urb_layout::isocBlockBytes(1), 0);
        urb.buffer.assign(4, 0);
        feedbackUrbs_.push_back(std::move(urb));
    }
    for (FeedbackUrb& urb : feedbackUrbs_) {
        if (urb.submitted) continue;
        usbdevfs_urb* u = urb_layout::urb(urb.block);
        memset(u, 0, sizeof(*u));
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char) alt_.feedbackAddress;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer = urb.buffer.data();
        u->buffer_length = (int) urb.buffer.size();
        u->number_of_packets = 1;
        urb_layout::desc(urb.block, 0)->length = (int) urb.buffer.size();
        if (ioctl(fd_, USBDEVFS_SUBMITURB, u) == 0) {
            urb.submitted = true;
        } else {
            LOGW("feedback submit failed: %s", strerror(errno));
            alt_.feedbackAddress = 0;  // pace at the nominal rate from now on
            return;
        }
    }
}

void UacStream::refillUrb(IsoUrb& urb) {
    // Caller holds mutex_. Size each packet from the pacing accumulator, fill
    // from the ring and pad with silence when it runs dry (the device must
    // keep receiving packets or it mutes).
    int64_t maxPacket = alt_.epMaxPacket;
    if (highSpeed_) {
        // wMaxPacketSize bits 11-12 add extra transactions per microframe.
        int extra = (alt_.epMaxPacket >> 11) & 3;
        (void) extra;  // epMaxPacket was masked to 10 bits while parsing
        maxPacket = alt_.epMaxPacket;
    }
    int64_t pacingRate = feedbackRate_ > 0 ? feedbackRate_ : rate_;
    int64_t totalBytes = 0;
    for (int p = 0; p < packetsPerUrb_; p++) {
        packetAcc_ += pacingRate * intervalUs_;
        int64_t frames = packetAcc_ / 1000000;
        packetAcc_ %= 1000000;
        int64_t packetBytes = std::min(frames * frameBytesDac_, maxPacket);
        urb_layout::desc(urb.block, p)->length = (int) packetBytes;
        totalBytes += packetBytes;
    }
    urb.buffer.assign((size_t) totalBytes, 0);
    int64_t fromRing = std::min(ringBytes_, totalBytes);
    int64_t offset = 0;
    int64_t take = fromRing;
    while (take > 0) {
        size_t tail = ring_.size() - ringRead_;
        size_t chunk = (size_t) std::min<int64_t>((int64_t) tail, take);
        memcpy(urb.buffer.data() + offset, ring_.data() + ringRead_, chunk);
        ringRead_ = (ringRead_ + chunk) % ring_.size();
        offset += (int64_t) chunk;
        take -= (int64_t) chunk;
    }
    ringBytes_ -= fromRing;
    urb.dataBytes = fromRing;
    inFlightBytes_ += fromRing;
    if (fromRing == 0 && totalBytes > 0) starvedUrbs_++;

    // Payload audit (observer only). A buffer that is constant across its whole
    // span — all-zero silence padding, or a dead downstream stage — is the one
    // condition the device cannot distinguish from "music", so it is latched
    // and reported only once it persists past the grace window (a single
    // zero-filled URB during a fade or underrun is a legitimate transient).
    if (totalBytes > 0) {
        bool constant = true;
        const uint8_t first = urb.buffer[0];
        for (size_t i = 1; i < urb.buffer.size(); i++) {
            if (urb.buffer[i] != first) { constant = false; break; }
        }
        int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        if (!constant) {
            if (!payloadLive_) LOGI("payload audit: live audio back on the wire");
            payloadConstantSinceMs_ = -1;
            payloadStuckSinceMs_ = -1;
            payloadLive_ = true;
        } else if (payloadConstantSinceMs_ < 0) {
            payloadConstantSinceMs_ = nowMs;
        } else if (payloadLive_ && nowMs - payloadConstantSinceMs_ > 1500) {
            payloadLive_ = false;
            payloadStuckSinceMs_ = payloadConstantSinceMs_;
            LOGW("payload audit: output has been constant (all-zero) for >1.5s");
        }
    }

    usbdevfs_urb* u = urb_layout::urb(urb.block);
    memset(u, 0, sizeof(*u));
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char) alt_.epAddress;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = urb.buffer.data();
    u->buffer_length = (int) totalBytes;
    u->number_of_packets = packetsPerUrb_;
    if (ioctl(fd_, USBDEVFS_SUBMITURB, u) == 0) {
        urb.submitted = true;
    } else {
        urb.submitted = false;
        inFlightBytes_ -= urb.dataBytes;
        urb.dataBytes = 0;
        LOGW("iso submit failed: %s", strerror(errno));
    }
}

int UacStream::reapUrb(int timeoutMs) {
    struct pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLIN;
    poll(&pfd, 1, timeoutMs);
    int reapedCount = 0;
    while (true) {
        void* reaped = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &reaped) != 0 || reaped == nullptr) {
            break;
        }
        usbdevfs_urb* urb = (usbdevfs_urb*) reaped;
        reapedCount++;
        std::lock_guard<std::mutex> guard(mutex_);
        if (urb->endpoint == (unsigned char) alt_.feedbackAddress
                && alt_.feedbackAddress != 0) {
            for (FeedbackUrb& fb : feedbackUrbs_) {
                if (urb_layout::urb(fb.block) == urb) {
                    fb.submitted = false;
                    int got = urb->iso_frame_desc[0].actual_length;
                    if (urb->status == 0 && got >= 3) {
                        uint64_t value = 0;
                        for (int i = 0; i < got && i < 4; i++) {
                            value |= (uint64_t) fb.buffer[i] << (8 * i);
                        }
                        int64_t rate;
                        if (got >= 4 && uac2_) {
                            rate = (int64_t) ((value * 8000) >> 16);   // Q16.16
                        } else if (highSpeed_) {
                            rate = (int64_t) ((value * 8000) >> 13);   // Q12.13
                        } else {
                            rate = (int64_t) ((value * 1000) >> 10);   // Q10.10
                        }
                        // An adaptive DAC legitimately asks for slightly more or
                        // less than nominal (its own crystal), so a valid reply
                        // can sit outside a tight window. Clamp to a wide ±50%
                        // envelope to reject outright garbage, then smooth with
                        // an EWMA. Discarding valid-but-unusual replies would
                        // pin pacing to nominal, let the ring drift against the
                        // DAC clock, and end in silence.
                        int64_t lo = (int64_t) rate_ * 5 / 10;
                        int64_t hi = (int64_t) rate_ * 15 / 10;
                        if (rate >= lo && rate <= hi) {
                            feedbackRate_ = feedbackRate_ > 0
                                    ? (feedbackRate_ * 4 + rate) / 5
                                    : rate;
                        }
                    }
                    break;
                }
            }
        } else {
            for (IsoUrb& iso : urbs_) {
                if (urb_layout::urb(iso.block) == urb) {
                    iso.submitted = false;
                    inFlightBytes_ -= std::min(inFlightBytes_, iso.dataBytes);
                    iso.dataBytes = 0;
                    break;
                }
            }
        }
        spaceAvailable_.notify_all();
    }
    return reapedCount;
}

void UacStream::feedLoop() {
    // Android preempts ordinary threads freely; a feeder that waits behind UI
    // work is exactly what empties the wire and makes the DAC stutter. Ask for
    // the audio priority (a no-op where the kernel refuses it).
    if (setpriority(PRIO_PROCESS, 0, -16) != 0) {
        LOGW("feed thread priority not raised: %s", strerror(errno));
    }
    // Heartbeat: silently stalling looks identical to "playing" from Java (the
    // device just gets silence), so the loop reports its own flow counters.
    // Diagnosing "full blast then silence" needs exactly this: ring vs. in-flight
    // separates a starving writer from a device that stopped consuming.
    auto lastBeat = std::chrono::steady_clock::now();
    int64_t beatReaps = 0;
    int64_t lastWritten = 0;
    int64_t lastStarved = 0;
    int64_t lastFlushes = 0;
    while (running_) {
        {
            std::unique_lock<std::mutex> guard(mutex_);
            // The feedback endpoint must be re-armed after every completion: its
            // replies are the only measurement of the DAC's real consumption
            // rate. Submitting it once, before the loop (as this used to), latched
            // pacing to the first two replies — taken while the DAC's clock was
            // still settling — so the stream then ran at a slightly wrong rate and
            // periodically starved the device. That is the stutter.
            submitFeedback();
            bool submittedAny = false;
            for (IsoUrb& urb : urbs_) {
                if (urb.submitted) continue;
                refillUrb(urb);
                if (urb.submitted) submittedAny = true;
            }
            if (!submittedAny) {
                dataAvailable_.wait_for(guard, std::chrono::milliseconds(20));
            }
        }
        beatReaps += reapUrb(10);
        auto now = std::chrono::steady_clock::now();
        if (now - lastBeat >= std::chrono::seconds(2)) {
            {
                std::lock_guard<std::mutex> guard(mutex_);
                int inFlight = 0;
                for (IsoUrb& urb : urbs_) {
                    if (urb.submitted) inFlight++;
                }
                int64_t writtenDelta = totalWrittenBytes_ - lastWritten;
                int64_t starvedDelta = starvedUrbs_ - lastStarved;
                int64_t flushDelta = flushCount_ - lastFlushes;
                lastWritten = totalWrittenBytes_;
                lastStarved = starvedUrbs_;
                lastFlushes = flushCount_;
                LOGI("beat: wrote=%lldB/2s ring=%lld/%zu inflight=%lldB urbs=%d"
                     " reaps=%lld starved=%lld flushes=%lld flushedTotal=%lldB"
                     " payload=%s fbRate=%lld",
                     (long long) writtenDelta, (long long) ringBytes_, ring_.size(),
                     (long long) inFlightBytes_, inFlight, (long long) beatReaps,
                     (long long) starvedDelta, (long long) flushDelta,
                     (long long) flushedBytes_,
                     payloadLive_ ? "live" : "stuck",
                     (long long) feedbackRate_);
            }
            // (The DAC's own volume is deliberately NOT read back here. This
            // DAC refuses GET_CUR while the isochronous stream runs, and a
            // refused control transfer costs its full timeout on THIS thread —
            // the one that must refill URBs every few milliseconds. A 200 ms
            // stall here drained the in-flight buffer and stuttered the stream
            // every beat. Use the volprobe debug command for readbacks.)
            lastBeat = now;
            beatReaps = 0;
        }
    }
    reapUrb(0);
}

void UacStream::teardown() {
    stopStreaming();
    if (opened_ && fd_ >= 0) {
        usbdevfs_setinterface set;
        memset(&set, 0, sizeof(set));
        set.interface = (unsigned int) alt_.interfaceNumber;
        set.altsetting = 0;
        ioctl(fd_, USBDEVFS_SETINTERFACE, &set);
        for (int iface : info_.audioInterfaces) {
            unsigned int number = (unsigned int) iface;
            ioctl(fd_, USBDEVFS_RELEASEINTERFACE, &number);
        }
        // The kernel driver reattaches when the framework rediscovers the
        // device (worst case: replug). HiBy behaves the same way.
    }
    urbs_.clear();
    feedbackUrbs_.clear();
    opened_ = false;
    streaming_ = false;
    fd_ = -1;
}

UacStream gStream;

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_open(
        JNIEnv*, jclass, jint fd, jint rate, jint channels, jboolean floatPcm,
        jint preferDepth) {
    return gStream.open(fd, rate, channels, floatPcm == JNI_TRUE, preferDepth)
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_start(JNIEnv*, jclass) {
    gStream.start();
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_pause(JNIEnv*, jclass) {
    gStream.pause();
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_flush(JNIEnv*, jclass) {
    gStream.flush();
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_setVolume(
        JNIEnv*, jclass, jfloat volume) {
    gStream.setVolume(volume);
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_setHardwareVolume(
        JNIEnv*, jclass, jfloat volume) {
    gStream.setHardwareVolume(volume);
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_setSoftwareVolume(
        JNIEnv*, jclass, jfloat volume) {
    gStream.setSoftwareVolume(volume);
}

JNIEXPORT jboolean JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_hasHardwareVolume(JNIEnv*, jclass) {
    return gStream.hasHardwareVolume() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_volProbe(JNIEnv*, jclass) {
    gStream.probeVolume();
}

JNIEXPORT jint JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_write(
        JNIEnv* env, jclass, jobject buffer, jint bytes) {
    if (buffer == nullptr || bytes <= 0) return 0;
    auto* src = (const uint8_t*) env->GetDirectBufferAddress(buffer);
    if (src == nullptr) return -1;
    return gStream.write(src, bytes);
}

JNIEXPORT jlong JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_framesQueued(JNIEnv*, jclass) {
    return gStream.framesQueued();
}

JNIEXPORT jboolean JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_exclusive(JNIEnv*, jclass) {
    return gStream.exclusive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_sampleRate(JNIEnv*, jclass) {
    return gStream.sampleRate();
}

JNIEXPORT jint JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_channels(JNIEnv*, jclass) {
    return gStream.channels();
}

JNIEXPORT jint JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_bitDepth(JNIEnv*, jclass) {
    return gStream.bitDepth();
}

JNIEXPORT jboolean JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_floatPcm(JNIEnv*, jclass) {
    return gStream.floatPcm() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(gStream.lastError().c_str());
}

JNIEXPORT void JNICALL
Java_dev_t1m3_qplayer_android_playback_UacOutput_close(JNIEnv*, jclass) {
    gStream.teardown();
}

} // extern "C"
