import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "."

// 音频链路 (QHIFI tab): a read-out of what the audio path is actually doing,
// not a setting — the rows above it are the controls, this card explains their
// current result. Every value is a player.hifi* Property, so it follows the
// backend without anything here polling or wiring signals.
//
// Kept as its own component (not inlined into SettingsPanel): qml4j compiles a
// whole document into one constructor and SettingsPanel is already large.
Item {
    id: block
    property string category: ""
    visible: block.category === "hifi"
    readonly property real captionHeight: 30
    implicitHeight: visible ? captionHeight + card.height : 0

    // HifiOutputMode's persisted index -> its label. Only two outcomes exist
    // (see HifiOutputMode): 0 system, 1 the app's userspace USB exclusive driver.
    function hifiModeLabel(id) {
        return i18n.t(id === 1 ? "settings.hifi.mode.exclusive"
                               : "settings.hifi.mode.system")
    }

    function hifiExclusiveLabel(state) {
        return i18n.t(state === "full" ? "settings.hifiChain.exclusive.full"
                      : state === "partial" ? "settings.hifiChain.exclusive.partial"
                      : "settings.hifiChain.exclusive.none")
    }

    SettingCaption {
        x: Ui.space4
        y: 2
        width: Math.max(0, block.width - Ui.space8)
        text: i18n.t("settings.hifiChain.title")
    }

    SettingCard {
        id: card
        y: block.captionHeight
        width: block.width
        height: implicitHeight

        // Manual refresh: the readout below is driven by the backend, but a DAC
        // plugged in (or a grant just made) is not always noticed instantly —
        // these two re-query on demand, one for the device list, one for the
        // whole chain.
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Ui.space4
            Layout.rightMargin: Ui.space4
            Layout.topMargin: 4
            spacing: Ui.space2
            Button {
                type: "text"
                icon: "refresh"
                text: i18n.t("settings.hifiChain.refreshDevices")
                onClicked: player.refreshHifiDevices()
            }
            Button {
                type: "text"
                icon: "refresh"
                text: i18n.t("settings.hifiChain.refresh")
                onClicked: player.refreshHifiChain()
            }
            Item { Layout.fillWidth: true }
        }

        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            title: i18n.t("settings.hifiChain.mode.title")
            value: block.hifiModeLabel(player.hifiOutputMode)
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.effective.title")
            // Only the fallback case is worth spelling out; when the requested
            // mode is the one running, the row above already says which.
            value: player.hifiEffectiveMode === player.hifiOutputMode
                   ? block.hifiModeLabel(player.hifiEffectiveMode)
                   : i18n.t("settings.hifiChain.effective.fallback")
                     + " · " + block.hifiModeLabel(player.hifiEffectiveMode)
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.exclusive.title")
            value: block.hifiExclusiveLabel(player.hifiExclusiveState)
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.source.title")
            value: player.hifiSourceFormat
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.output.title")
            value: player.hifiDeviceName
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            visible: player.hifiUacVersion !== ""
            title: i18n.t("settings.hifiChain.uac.title")
            value: player.hifiUacVersion
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.format.title")
            value: player.hifiOutputFormat
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.status.title")
            value: i18n.t(player.hifiResampled
                          ? "settings.hifiChain.status.resampled"
                          : "settings.hifiChain.status.direct")
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.capability.title")
            value: i18n.t(!player.hifiBitPerfectSupported
                          ? "settings.hifiChain.capability.unsupported"
                          : player.hifiBitPerfectActive
                            ? "settings.hifiChain.capability.active"
                            : "settings.hifiChain.capability.available")
        }
        SettingRowFrame {
            Layout.fillWidth: true
            clickable: false
            separator: true
            title: i18n.t("settings.hifiChain.capability.usb.title")
            value: i18n.t(player.hifiUsbDevicePresent
                          ? "settings.hifiChain.capability.usb.present"
                          : "settings.hifiChain.capability.usb.absent")
        }

        // The one row that is a sentence rather than a label/value pair: a
        // wrapping line of its own, so it never has to fight the row layout for
        // width. A fallback outranks the mode's own note — it is the more
        // specific thing to say.
        Text {
            Layout.fillWidth: true
            Layout.leftMargin: Ui.space4
            Layout.rightMargin: Ui.space4
            Layout.bottomMargin: Ui.space3
            Layout.topMargin: Ui.space1
            visible: player.hifiFallbackReason.length > 0
                     || player.hifiReason.length > 0
            text: i18n.t("settings.hifiChain.reason.title") + ": "
                  + (player.hifiFallbackReason.length > 0
                     ? player.hifiFallbackReason : player.hifiReason)
            color: Ui.textSecondary
            font.pixelSize: Ui.textCallout
            wrapMode: Text.WordWrap
        }
    }
}
