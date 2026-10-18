import QtQuick
import miuix.Core

// Volume HUD, HiBy-style: any volume change (keys, slider) pops a card at the
// top that names what the level drives — the DAC's own control in hardware
// mode, the app's digital gain otherwise — and carries a slider that stays live
// while the card is up. It fades out once the level sits still for a moment.
// Volume keys are taken over by the exclusive driver (the framework's own key
// handling targets a stream nothing plays through then), so this card is their
// visible feedback, replacing the raw toast.
//
// A component of its own, not a block inside Main.qml: qml4j compiles a whole
// document into ONE constructor method, and ASM caps a method at 64 KB of
// bytecode — Main.qml sits right at that ceiling (see the note on its VolumeHud
// instantiation). Anything sizeable added to Main.qml belongs in a file like
// this one.
//
// The card is exclusive-only: elsewhere the level is the host's own plain app
// volume, with upstream's chrome as its control and feedback, and a
// hardware-vs-software readout would say nothing.
Rectangle {
    id: volumeHud
    z: 25000

    property bool hifiLevelLive: player.hifiVolumeKind !== ""
    property bool shown: false

    property real volumeWatch: player.volume
    onVolumeWatchChanged: if (hifiLevelLive) volumeHud.poke()
    // Losing the exclusive stream (switch off, DAC unplugged, fallback) takes the
    // card with it — its label would otherwise sit there describing a level that
    // is no longer ours.
    onHifiLevelLiveChanged: if (!hifiLevelLive) volumeHud.shown = false

    // The stored app volume follows the level the backend is really playing at —
    // the DAC's own control adopted at open, the volume keys, this card's slider —
    // instead of owning it, so a later settings push can never write a stale
    // value back over a DAC-owned level. Debounced: a drag fires far more level
    // changes than the store needs to see.
    property real appVolumeWatch: player.volume
    onAppVolumeWatchChanged: appVolumeCommit.restart()

    visible: opacity > 0.01
    opacity: shown ? 1 : 0
    Behavior on opacity { NumberAnimation { duration: 160 } }
    width: Math.min(420, parent.width - 32)
    height: 88
    radius: 18
    color: Theme.color.surfaceContainerHigh

    function poke() {
        shown = true
        if (!hudSlider.pressed) hudSlider.value = player.volume
        hudHide.restart()
    }

    Text {
        id: hudTitle
        anchors.top: parent.top
        anchors.topMargin: 12
        anchors.horizontalCenter: parent.horizontalCenter
        fontSize: 14
        color: Theme.color.onSurfaceColor
        text: (player.hifiVolumeKind === "hardware"
                   ? i18n.t("settings.hifiVolume.hardware")
                   : player.hifiVolumeKind === "software"
                       ? i18n.t("settings.hifiVolume.software")
                       : i18n.t("settings.hifiVolume.generic"))
              + " · " + Math.round(player.volume * 100) + "%"
    }

    Slider {
        id: hudSlider
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: 12
        from: 0
        to: 1
        value: player.volume
        onMoved: {
            volumeHud.shown = true
            hudHide.restart()
            player.setVolume(value)
        }
    }

    Timer {
        id: appVolumeCommit
        interval: 400
        onTriggered: settings.noteVolumeFromHost(player.volume)
    }

    Timer {
        id: hudHide
        interval: 2200
        onTriggered: volumeHud.shown = false
    }
}
