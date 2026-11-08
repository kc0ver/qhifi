import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: a cassette face with two reels that turn during playback and a side
// A/B switch for the label (the queue itself is untouched).
Rectangle {
    id: card
    property bool active: true
    property int side: 0

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    ColumnLayout {
        id: col
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.tape.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.tape.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 150
            Layout.topMargin: 4
            radius: 14
            color: "#171009"
            border.width: 1
            border.color: "#4E3420"

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.topMargin: 22
                anchors.leftMargin: 18
                anchors.rightMargin: 18
                height: 34
                radius: 8
                color: "#0A0704"
                border.width: 1
                border.color: "#3A2818"

                Rectangle {
                    height: 5
                    radius: 2
                    anchors.left: parent.left
                    anchors.leftMargin: 4
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 5
                    width: (parent.width - 8) * Math.max(0, Math.min(1, player.lyricProgress))
                    color: "#C98A3E"
                }
            }

            Item {
                id: leftReel
                width: 66
                height: 66
                x: 30
                y: 50

                Rectangle {
                    anchors.fill: parent
                    radius: 33
                    color: "#241A12"
                    border.width: 2
                    border.color: "#6A4A2A"
                }
                Rectangle {
                    width: 4
                    height: 48
                    x: 31
                    y: 9
                    color: "#8A6238"
                }
                Rectangle {
                    width: 48
                    height: 4
                    x: 9
                    y: 31
                    color: "#8A6238"
                }
                Rectangle {
                    width: 14
                    height: 14
                    radius: 7
                    anchors.centerIn: parent
                    color: "#D8B26A"
                }
            }

            Item {
                id: rightReel
                width: 48
                height: 48
                x: parent.width - 78
                y: 58

                Rectangle {
                    anchors.fill: parent
                    radius: 24
                    color: "#241A12"
                    border.width: 2
                    border.color: "#6A4A2A"
                }
                Rectangle {
                    width: 3
                    height: 34
                    x: 22
                    y: 7
                    color: "#8A6238"
                }
                Rectangle {
                    width: 34
                    height: 3
                    x: 7
                    y: 22
                    color: "#8A6238"
                }
                Rectangle {
                    width: 10
                    height: 10
                    radius: 5
                    anchors.centerIn: parent
                    color: "#D8B26A"
                }
            }

            Text {
                anchors.left: parent.left
                anchors.leftMargin: 16
                anchors.top: parent.top
                anchors.topMargin: 4
                text: "TDK · " + (card.side === 0 ? i18n.t("funlab.tape.sideA")
                                                 : i18n.t("funlab.tape.sideB"))
                color: "#C9A45C"
                fontSize: 10
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            Button {
                Layout.fillWidth: true
                type: card.side === 0 ? "filledTonal" : "outlined"
                text: i18n.t("funlab.tape.sideA")
                onClicked: card.side = 0
            }
            Button {
                Layout.fillWidth: true
                type: card.side === 1 ? "filledTonal" : "outlined"
                text: i18n.t("funlab.tape.sideB")
                onClicked: card.side = 1
            }
            IconButton {
                type: "standard"
                icon: player.playing ? "pause" : "play_arrow"
                onClicked: player.toggle()
            }
            IconButton {
                type: "standard"
                icon: "skip_next"
                onClicked: player.next()
            }
        }
    }

    Timer {
        interval: 32
        repeat: true
        running: card.active && player.playing
        onTriggered: {
            leftReel.rotation = (leftReel.rotation + 4) % 360
            rightReel.rotation = (rightReel.rotation - 2.8) % 360
        }
    }
}
