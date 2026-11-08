import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: a spinning record with the now-playing cover and a tonearm that
// drops on play. The switch below mirrors the lyric page's existing vinyl look.
Rectangle {
    id: card
    property bool active: true

    radius: 20
    color: "#2A1C14"
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
            text: i18n.t("funlab.vinyl.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.vinyl.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 214
            Layout.topMargin: 4

            Rectangle {
                id: platter
                width: 196
                height: 196
                radius: 98
                anchors.centerIn: parent
                color: "#0B0B0B"
                border.width: 3
                border.color: "#2E2E2E"

                Rectangle {
                    width: parent.width * 0.90
                    height: width
                    radius: width / 2
                    anchors.centerIn: parent
                    color: "transparent"
                    border.width: 1
                    border.color: "#1C1C1C"
                }
                Rectangle {
                    width: parent.width * 0.78
                    height: width
                    radius: width / 2
                    anchors.centerIn: parent
                    color: "transparent"
                    border.width: 1
                    border.color: "#1C1C1C"
                }

                CoverImage {
                    width: 124
                    height: 124
                    radius: 62
                    anchors.centerIn: parent
                    source: player.coverPath
                    iconSize: 36
                }

                Rectangle {
                    width: 10
                    height: 10
                    radius: 5
                    anchors.centerIn: parent
                    color: "#E8D8C0"
                    border.width: 1
                    border.color: "#7A5A34"
                }
            }

            Rectangle {
                id: armPivot
                width: 18
                height: 18
                radius: 9
                x: parent.width / 2 + 86
                y: 6
                color: "#3A2A1C"
                border.width: 1
                border.color: "#C9A45C"
            }

            Rectangle {
                id: tonearm
                width: 5
                height: 92
                x: armPivot.x + 6
                y: armPivot.y + 14
                radius: 2
                color: "#C9A45C"
                transformOrigin: Item.Top
                rotation: player.playing ? 24 : -8
                Behavior on rotation {
                    NumberAnimation { duration: 420; easing.type: Easing.OutCubic }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.bottom: parent.bottom
                text: player.title.length > 0 ? player.title : i18n.t("funlab.vinyl.title")
                color: "#D9B98C"
                fontSize: 12
                elide: Text.ElideRight
                width: parent.width - 48
                horizontalAlignment: Text.AlignHCenter
            }
        }

        Button {
            Layout.fillWidth: true
            type: card.vinylLook ? "filledTonal" : "outlined"
            text: i18n.t("funlab.vinyl.lyric") + " · "
                  + (card.vinylLook ? i18n.t("funlab.enableOnLyric") : "")
            property bool vinylLook: settings.value("lyricCoverStyle") === 1
            onClicked: settings.setValue("lyricCoverStyle", card.vinylLook ? 0 : 1)
        }
    }

    Timer {
        interval: 32
        repeat: true
        running: card.active && player.playing
        onTriggered: platter.rotation = (platter.rotation + 0.7) % 360
    }
}
