import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: a retro spectrum/wave toy. Two renderings share one level array.
Rectangle {
    id: card
    property bool active: true

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    property var amps: []
    property int style: 0

    ColumnLayout {
        id: col
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.scope.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.scope.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 104
            Layout.topMargin: 4
            radius: 10
            color: "#0E0A07"
            border.width: 1
            border.color: "#3E2A1A"

            Item {
                id: scope
                anchors.fill: parent
                anchors.margins: 8

                Repeater {
                    model: 24
                    delegate: Rectangle {
                        property real amp: card.amps.length > index ? card.amps[index] : 0
                        width: 7
                        radius: card.style === 0 ? 2 : 3
                        color: "#E0A24A"
                        opacity: 0.35 + 0.65 * amp
                        x: index * ((scope.width - 7) / 23)
                        height: card.style === 0 ? 4 + amp * (scope.height - 6) : 5
                        y: card.style === 0
                           ? scope.height - height
                           : scope.height - 5 - amp * (scope.height - 8)
                    }
                }

                Rectangle {
                    anchors.bottom: parent.bottom
                    anchors.left: parent.left
                    anchors.right: parent.right
                    height: 1
                    color: "#3E2A1A"
                }
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            Button {
                Layout.fillWidth: true
                type: card.style === 0 ? "filledTonal" : "outlined"
                text: i18n.t("funlab.scope.bars")
                onClicked: card.style = 0
            }
            Button {
                Layout.fillWidth: true
                type: card.style === 1 ? "filledTonal" : "outlined"
                text: i18n.t("funlab.scope.dots")
                onClicked: card.style = 1
            }
        }
    }

    Timer {
        interval: 60
        repeat: true
        running: card.active
        onTriggered: {
            var t = Date.now() / 1000
            var next = []
            for (var i = 0; i < 24; i++) {
                var prev = card.amps.length > i ? card.amps[i] : 0
                var target = player.playing
                             ? 0.12 + 0.80 * Math.abs(Math.sin(t * (0.8 + i * 0.07) + i))
                                      * (0.55 + 0.45 * Math.random())
                             : 0
                next.push(prev * 0.55 + target * 0.45)
            }
            card.amps = next
        }
    }
}
