import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Playback info for the lyric page: a centred modal card over the chrome that says
// where the current track comes from, what its own format is, and what the HiFi
// output chain is really doing (negotiated format, exclusivity, device).
//
// NOT a miuix Dialog on purpose: Dialog.open() reparents its content to the true
// QML root, but the lyric page is composited from the "lyricChrome" subtree alone
// (LyricCompositor), so a reparented popup would never be drawn — the same trap
// the offset panel documents. It is a child of the chrome, and it lives in a file
// of its own because qml4j compiles a whole document into ONE constructor method
// (64 KB cap) and LyricOverlay.qml is already large (see VolumeHud.qml).
//
// The open state stays with LyricOverlay (chainPanelOpen): it has to fold when the
// page closes, it excludes the offset panel, and it drives the host's lyric-gesture
// suppression flag (PlayerController.lyricOffsetPanelOpen) while it is up.
Item {
    id: root
    property bool opened: false
    signal closeRequested()

    // Re-read the chain on the way in: a DAC plugged in (or a grant just made) is
    // not always noticed instantly, and this card is exactly where the user looks
    // to confirm what the output is. Same call as the settings card's refresh.
    onOpenedChanged: if (opened) player.refreshHifiChain()

    // An empty value reads as "not known" rather than as a blank row, and a long
    // one (a DAC product name) is cut here rather than left to elide: the row gives
    // the label the flexible width and the value only what its text needs.
    function display(value) {
        if (!value || value.length === 0) return "—"
        return value.length > 22 ? value.substring(0, 21) + "…" : value
    }

    function exclusiveLabel(state) {
        return i18n.t(state === "full" ? "settings.hifiChain.exclusive.full"
                    : state === "partial" ? "settings.hifiChain.exclusive.partial"
                    : "settings.hifiChain.exclusive.none")
    }

    // Tube model numbers are language-neutral, so the tone row composes them
    // directly instead of going through the settings-page option labels (those
    // carry a "· 甜厚" suffix meant for the segmented control).
    function tubeName(index) {
        return index === 1 ? "EL34" : index === 2 ? "6L6" : "300B"
    }

    // One binding that reads every player.* it depends on inline: qml4j registers a
    // binding's dependencies from the reads in the expression itself, so the reads
    // must not hide inside a helper function (only the pure formatting does).
    property var rows: {
        var source = player.currentFilePath !== "" ? i18n.t("source.local") : ""
        if (source === "") {
            var mediaId = player.currentMediaId
            var colon = mediaId.indexOf(":")
            var provider = colon > 0 ? mediaId.substring(0, colon) : ""
            if (provider !== "") {
                var plugins = player.sourcePlugins || []
                for (var i = 0; i < plugins.length; i++) {
                    if (plugins[i].id === provider) { source = plugins[i].name; break }
                }
                if (source === "") source = provider
            }
        }
        return [
            { label: i18n.t("lyric.chain.source"), value: root.display(source) },
            // A stream's header is fetched over the network as part of the chain
            // query, so the row says "reading" while that runs instead of claiming
            // the format is unknown (which is what an empty value means).
            { label: i18n.t("lyric.chain.sourceFormat"),
              value: player.hifiSourceFormatPending
                     ? i18n.t("lyric.chain.sourceFormat.reading")
                     : root.display(player.hifiSourceFormat) },
            { label: i18n.t("settings.hifiChain.format.title"),
              value: root.display(player.hifiOutputFormat) },
            // The "no silent coloration" rule: with tube tone on, the card says
            // so (preset + intensity) instead of implying a clean passthrough.
            { label: i18n.t("settings.hifiChain.tone.title"),
              value: settings.value("hifiTubeTone") === true
                     ? i18n.t("settings.hifiChain.tone.tube") + " · "
                       + root.tubeName(settings.value("hifiTubePreset")) + " · "
                       + settings.value("hifiTubeAmount") + "%"
                     : i18n.t("settings.hifiChain.tone.direct") },
            { label: i18n.t("settings.hifiChain.exclusive.title"),
              value: root.exclusiveLabel(player.hifiExclusiveState) },
            { label: i18n.t("settings.hifiChain.output.title"),
              value: root.display(player.hifiDeviceName) }
        ]
    }

    z: 20
    // Keep painting until the exit fade reaches zero — binding visible straight to
    // opened would cut the scale-down off on its first frame.
    visible: opened || opacity > 0.01
    opacity: opened ? 1 : 0
    Behavior on opacity { NumberAnimation { duration: 160 } }

    Rectangle {
        id: scrim
        anchors.fill: parent
        color: "#73000000"
        MouseArea {
            anchors.fill: parent
            onClicked: root.closeRequested()
        }
    }

    SmoothRectangle {
        id: card
        anchors.centerIn: parent
        width: Math.min(360, root.width - 32)
        height: content.implicitHeight + 40
        radius: 24
        color: Theme.color.surfaceContainerHigh
        borderWidth: 1
        borderColor: Theme.color.outlineVariant
        scale: root.opened ? 1 : 0.92
        Behavior on scale {
            NumberAnimation {
                duration: root.opened ? 200 : 150
                easing.type: root.opened ? Easing.OutCubic : Easing.InCubic
            }
        }
        // Swallow taps on the card itself; only the scrim around it closes it.
        MouseArea { anchors.fill: parent }

        ColumnLayout {
            id: content
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 20
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Text {
                    Layout.fillWidth: true
                    text: i18n.t("lyric.chain.title")
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.titleMedium.family
                    font.pixelSize: Theme.typography.titleMedium.size
                    font.weight: Theme.typography.titleMedium.weight
                }
                IconButton {
                    type: "standard"
                    icon: "close"
                    contentColor: Theme.color.onSurfaceVariantColor
                    onClicked: root.closeRequested()
                }
            }

            Repeater {
                model: root.rows
                delegate: RowLayout {
                    Layout.fillWidth: true
                    spacing: 12
                    Text {
                        Layout.fillWidth: true
                        text: modelData.label
                        color: Theme.color.onSurfaceVariantColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: 14
                        elide: Text.ElideRight
                    }
                    Text {
                        text: modelData.value
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.bodySmall.family
                        font.pixelSize: 14
                        horizontalAlignment: Text.AlignRight
                        elide: Text.ElideRight
                    }
                }
            }
        }
    }
}
