import QtQuick
import "."

// Lyric-page VU pair, toggled from the fun lab. Sits above the transport band,
// left side, inside the chrome subtree the lyric compositor redraws.
Item {
    id: root
    z: 3

    property bool hostOpen: player.lyricSlide > 0.001
    property bool enabledOnPage: settings.value("funLabEnabled") === true
                                 && settings.value("funVuMeter") === true
    visible: enabledOnPage && hostOpen

    property real levelA: 0
    property real levelB: 0

    width: 214
    height: 104
    anchors.left: parent.left
    anchors.leftMargin: 16
    anchors.bottom: parent.bottom
    anchors.bottomMargin: settings.bottomInset + 152

    Rectangle {
        anchors.fill: parent
        radius: 14
        color: "#8C120C08"
        border.width: 1
        border.color: "#7A5230"
    }

    Row {
        anchors.centerIn: parent
        spacing: 8

        FunVuMeter {
            width: 96
            height: root.height - 16
            level: root.levelA
            label: "L"
        }
        FunVuMeter {
            width: 96
            height: root.height - 16
            level: root.levelB
            label: "R"
        }
    }

    Timer {
        interval: 70
        repeat: true
        running: root.visible && root.hostOpen
        onTriggered: {
            var t = Date.now() / 1000
            var base = player.playing
                       ? 0.18 + 0.42 * Math.abs(Math.sin(t * 1.4)) + 0.16 * Math.random()
                       : 0
            var baseB = player.playing
                        ? 0.16 + 0.40 * Math.abs(Math.sin(t * 1.1 + 1.2)) + 0.16 * Math.random()
                        : 0
            root.levelA = root.levelA * 0.5 + base * 0.5
            root.levelB = root.levelB * 0.5 + baseB * 0.5
        }
    }
}
