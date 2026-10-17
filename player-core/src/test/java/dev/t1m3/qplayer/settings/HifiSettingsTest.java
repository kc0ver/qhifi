package dev.t1m3.qplayer.settings;

import dev.t1m3.qplayer.audio.HifiOutputMode;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** HiFi catalog placement, platform scoping and defaults — the catalog only, so
 *  nothing here loads SettingsCore (that path pulls Skija's native font manager).
 *  Same approach as {@link OverlayLyricSettingsTest}. */
public class HifiSettingsTest {

    @Test
    public void hifiTabSitsBetweenPluginsAndAbout() {
        int index = SettingsCatalog.CATEGORIES.indexOf(SettingsCatalog.HIFI);
        assertTrue(SettingsCatalog.CATEGORIES.contains(SettingsCatalog.HIFI));
        assertEquals(SettingsCatalog.PLUGINS, SettingsCatalog.CATEGORIES.get(index - 1));
        assertEquals(SettingsCatalog.ABOUT, SettingsCatalog.CATEGORIES.get(index + 1));
    }

    @Test
    public void everyHifiRowIsAndroidOnly() {
        Set<String> android = keysOn(SettingsCatalog.HIFI, SettingsCatalog.ANDROID);
        assertEquals(4, android.size());
        assertTrue(android.contains(SettingsCatalog.HIFI_ENABLED_KEY));
        assertTrue(android.contains(SettingsCatalog.HIFI_FOLLOW_RATE_KEY));
        assertTrue(android.contains(SettingsCatalog.HIFI_VOLUME_MODE_KEY));
        assertTrue(android.contains("action:pickHifiDevice"));

        // Nothing for the HiFi category at all on desktop: categories() drops a
        // category with no visible rows, which is what keeps the tab off desktop.
        assertTrue(keysOn(SettingsCatalog.HIFI, SettingsCatalog.DESKTOP).isEmpty());
    }

    @Test
    public void masterSwitchIsOffAndGatesTheRest() {
        SettingSpec enabled = spec(SettingsCatalog.HIFI_ENABLED_KEY);
        assertEquals(SettingSpec.SWITCH, enabled.type);
        assertEquals(Boolean.FALSE, enabled.def);
        assertEquals(SettingsCatalog.ANDROID, enabled.platform);
        assertEquals("hifiOutput", enabled.group);
        assertEquals("", enabled.dependsOn);

        // The follow-rate switch is the only remaining row gated by the master
        // switch; the mode dropdown and bit-perfect toggle are gone entirely.
        SettingSpec dependent = spec(SettingsCatalog.HIFI_FOLLOW_RATE_KEY);
        assertEquals(SettingsCatalog.HIFI_ENABLED_KEY, dependent.dependsOn);
        assertEquals(SettingsCatalog.ANDROID, dependent.platform);
        assertEquals(Boolean.TRUE, dependent.def);
    }

    /** Only two output modes exist now — the system path and the app's own USB
     *  exclusive driver — so the enum's order is the whole persisted format. */
    @Test
    public void outputModesAreSystemAndExclusiveOnly() {
        assertEquals(2, HifiOutputMode.values().length);
        assertEquals(HifiOutputMode.SYSTEM, HifiOutputMode.fromId(0));
        assertEquals(HifiOutputMode.EXCLUSIVE, HifiOutputMode.fromId(1));
        assertEquals(HifiOutputMode.SYSTEM, HifiOutputMode.fromId(99));
    }

    @Test
    public void devicePickedFromTheHostDialogNotADropdown() {
        SettingSpec picker = spec("action:pickHifiDevice");
        assertEquals(SettingSpec.ACTION, picker.type);
        assertEquals("pickHifiDevice", picker.action);
        // A dynamic device list cannot be a dropdown over static options, so the
        // row shows the chosen device through a live-text provider instead.
        assertEquals("hifiDeviceName", picker.provider);
        assertTrue(picker.inlineProvider);
        assertEquals(SettingsCatalog.HIFI_ENABLED_KEY, picker.dependsOn);
        assertEquals(SettingsCatalog.ANDROID, picker.platform);

        SettingSpec stored = spec(SettingsCatalog.HIFI_DEVICE_KEY);
        assertEquals(SettingSpec.TEXT, stored.type);
        assertEquals("", stored.def);
        assertTrue(stored.hidden);
        assertEquals(SettingsCatalog.ANDROID, stored.platform);
    }

    private static SettingSpec spec(String key) {
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (key.equals(s.key)) return s;
        }
        throw new AssertionError("missing spec: " + key);
    }

    private static Set<String> keysOn(String category, String platform) {
        Set<String> out = new HashSet<>();
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (s.hidden || !category.equals(s.category) || !s.appliesTo(platform)) continue;
            out.add(s.key);
        }
        return out;
    }
}
