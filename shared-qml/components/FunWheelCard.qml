import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: a twelve-slot fortune wheel. The spin picks a real queue slot (the
// visible slots repeat if the queue is longer) and starts it when it stops.
Rectangle {
    id: card
    property bool active: true

    property real angle: 0
    property bool spinning: false
    property int pendingIndex: -1
    property string chosen: ""
    property string message: ""

    Behavior on angle {
        NumberAnimation {
            duration: 1600
            easing.type: Easing.OutCubic
        }
    }

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    function spin() {
        var queue = player.queueTracks || []
        if (queue.length === 0) {
            card.message = i18n.t("funlab.wheel.empty")
            return
        }
        card.message = ""
        var slot = Math.floor(Math.random() * 12)
        card.pendingIndex = slot % queue.length
        var want = ((360 - slot * 30) % 360 + 360) % 360
        var current = ((card.angle % 360) + 360) % 360
        var delta = 360 * 4 + ((want - current + 360) % 360)
        card.angle = card.angle + delta
        card.spinning = true
        spinDone.restart()
    }

    ColumnLayout {
        id: col
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.wheel.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.wheel.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 216

            Item {
                id: wheel
                width: 208
                height: 208
                anchors.centerIn: parent
                rotation: card.angle

                Rectangle {
                    anchors.fill: parent
                    radius: 104
                    color: "#140D08"
                    border.width: 3
                    border.color: "#7A4A24"
                }

                Repeater {
                    model: 12
                    delegate: Item {
                        width: 28
                        height: 28
                        property real rad: index * 30 * Math.PI / 180
                        x: 104 + Math.sin(rad) * 78 - 14
                        y: 104 - Math.cos(rad) * 78 - 14

                        Rectangle {
                            anchors.fill: parent
                            radius: 14
                            color: index % 2 === 0 ? "#3A2414" : "#2A1A10"
                            border.width: 1
                            border.color: "#8A6238"
                        }
                        Text {
                            anchors.centerIn: parent
                            text: (index + 1)
                            color: "#E0B878"
                            fontSize: 11
                            font.weight: Font.DemiBold
                        }
                    }
                }

                Rectangle {
                    width: 16
                    height: 16
                    radius: 8
                    anchors.centerIn: parent
                    color: "#C9A45C"
                    border.width: 2
                    border.color: "#3A2414"
                }
            }

            Rectangle {
                width: 11
                height: 14
                radius: 2
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.top: parent.top
                anchors.topMargin: 1
                color: "#E06A3A"
            }
        }

        Text {
            Layout.fillWidth: true
            visible: card.chosen.length > 0
            text: i18n.t("funlab.wheel.chosen", card.chosen)
            color: "#E0B878"
            font.pixelSize: 12
            elide: Text.ElideRight
        }
        Text {
            Layout.fillWidth: true
            visible: card.message.length > 0
            text: card.message
            color: "#D07A4A"
            font.pixelSize: 12
        }

        Button {
            Layout.fillWidth: true
            type: "filledTonal"
            text: i18n.t("funlab.wheel.spin")
            enabled: !card.spinning
            onClicked: card.spin()
        }
    }

    Timer {
        id: spinDone
        interval: 1700
        onTriggered: {
            card.spinning = false
            var queue = player.queueTracks || []
            if (card.pendingIndex >= 0 && card.pendingIndex < queue.length) {
                card.chosen = queue[card.pendingIndex].title || ""
                player.playQueueIndex(card.pendingIndex)
            }
        }
    }
}
