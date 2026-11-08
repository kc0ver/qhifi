import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: preview + switch for the wooden radio frame drawn around the lyric
// page (FunRadioFrame.qml).
Rectangle {
    id: card
    property bool active: true

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
            text: i18n.t("funlab.radio.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.radio.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 120
            Layout.topMargin: 4

            Rectangle {
                width: 230
                height: 104
                radius: 12
                anchors.centerIn: parent
                color: "#6B3C1C"
                border.width: 3
                border.color: "#2A160C"

                Rectangle {
                    id: dialFace
                    width: parent.width - 26
                    height: 44
                    radius: 6
                    anchors.top: parent.top
                    anchors.topMargin: 14
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: "#171009"
                    border.width: 1
                    border.color: "#C9A45C"

                    Rectangle {
                        width: 3
                        height: 32
                        x: dialFace.width * 0.34
                        y: 6
                        color: "#E0B878"
                    }
                    Repeater {
                        model: 11
                        delegate: Rectangle {
                            width: 1
                            height: 5
                            x: 8 + index * ((dialFace.width - 16) / 10)
                            y: 33
                            color: "#8A6238"
                        }
                    }
                }

                Rectangle {
                    width: 26
                    height: 26
                    radius: 13
                    anchors.right: parent.right
                    anchors.rightMargin: 16
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 14
                    color: "#D8B26A"
                    border.width: 2
                    border.color: "#3A2414"
                }
                Rectangle {
                    width: 18
                    height: 18
                    radius: 9
                    anchors.left: parent.left
                    anchors.leftMargin: 18
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 18
                    color: "#C9A45C"
                    border.width: 2
                    border.color: "#3A2414"
                }
                Rectangle {
                    width: 62
                    height: 12
                    radius: 3
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 18
                    color: "#8A6A32"
                    border.width: 1
                    border.color: "#C9A45C"
                }
            }
        }

        SuperSwitch {
            Layout.fillWidth: true
            title: i18n.t("funlab.radio.switch")
            checked: settings.value("funRadioSkin") === true
            property bool stored: settings.value("funRadioSkin") === true
            onStoredChanged: checked = stored
            onClicked: settings.setValue("funRadioSkin", checked)
        }
    }
}
