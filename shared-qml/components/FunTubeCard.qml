import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: two valve envelopes whose filaments warm up on play. The switch puts
// the same glow into the lyric page's bottom corners.
Rectangle {
    id: card
    property bool active: true

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    property real heat: player.playing ? 1.0 : 0.0
    Behavior on heat {
        NumberAnimation { duration: 2400; easing.type: Easing.InOutQuad }
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
            text: i18n.t("funlab.tube.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.tube.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 6
            spacing: 22
            Layout.alignment: Qt.AlignHCenter

            Item {
                Layout.preferredWidth: 82
                Layout.preferredHeight: 156

                Rectangle {
                    width: 62
                    height: 132
                    radius: 31
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 16
                    color: "#160C06"
                    border.width: 1
                    border.color: "#8A5A2A"
                    opacity: 0.85
                }
                Rectangle {
                    width: 48
                    height: 112
                    radius: 24
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 24
                    color: "#C97C28"
                    opacity: 0.10 + 0.38 * card.heat
                }
                Rectangle {
                    width: 3
                    height: 54
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 44
                    color: "#FFD894"
                    opacity: 0.20 + 0.80 * card.heat
                }
                Rectangle {
                    width: 22
                    height: 8
                    radius: 2
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 18
                    color: "#6A4A28"
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    text: "300B"
                    color: "#C9A45C"
                    fontSize: 10
                }
            }

            Item {
                Layout.preferredWidth: 82
                Layout.preferredHeight: 156

                Rectangle {
                    width: 62
                    height: 132
                    radius: 31
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 16
                    color: "#160C06"
                    border.width: 1
                    border.color: "#8A5A2A"
                    opacity: 0.85
                }
                Rectangle {
                    width: 48
                    height: 112
                    radius: 24
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 24
                    color: "#B96A1E"
                    opacity: 0.08 + 0.30 * card.heat
                }
                Rectangle {
                    width: 3
                    height: 50
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 46
                    color: "#FFD18C"
                    opacity: 0.16 + 0.70 * card.heat
                }
                Rectangle {
                    width: 22
                    height: 8
                    radius: 2
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 18
                    color: "#6A4A28"
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    text: "EL34"
                    color: "#C9A45C"
                    fontSize: 10
                }
            }
        }

        SuperSwitch {
            Layout.fillWidth: true
            title: i18n.t("funlab.tube.switch")
            checked: settings.value("funTubeWarmup") === true
            property bool stored: settings.value("funTubeWarmup") === true
            onStoredChanged: checked = stored
            onClicked: settings.setValue("funTubeWarmup", checked)
        }
    }
}
