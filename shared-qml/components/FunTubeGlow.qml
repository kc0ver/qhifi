import QtQuick

// Lyric-page tube glow: two valves sitting in the bottom corners, filaments
// warming up while playback runs and cooling once it pauses.
Item {
    id: root
    z: 1

    property bool hostOpen: player.lyricSlide > 0.001
    property bool enabledOnPage: settings.value("funLabEnabled") === true
                                 && settings.value("funTubeWarmup") === true
    visible: enabledOnPage && hostOpen

    property real heat: player.playing ? 1.0 : 0.0
    Behavior on heat {
        NumberAnimation {
            duration: 2400
            easing.type: Easing.InOutQuad
        }
    }

    anchors.fill: parent

    Item {
        width: 150
        height: 150
        anchors.left: parent.left
        anchors.leftMargin: -28
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 96
        opacity: 0.10 + 0.45 * root.heat

        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: "#B06A1E"
        }
        Rectangle {
            width: parent.width * 0.72
            height: width
            radius: width / 2
            anchors.centerIn: parent
            color: "#D98A2C"
        }
        Rectangle {
            width: parent.width * 0.40
            height: width
            radius: width / 2
            anchors.centerIn: parent
            color: "#FFD08A"
        }
    }

    Item {
        width: 120
        height: 120
        anchors.right: parent.right
        anchors.rightMargin: -20
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 120
        opacity: 0.08 + 0.34 * root.heat

        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: "#9A5A18"
        }
        Rectangle {
            width: parent.width * 0.70
            height: width
            radius: width / 2
            anchors.centerIn: parent
            color: "#C97C28"
        }
        Rectangle {
            width: parent.width * 0.38
            height: width
            radius: width / 2
            anchors.centerIn: parent
            color: "#FFCC84"
        }
    }
}
