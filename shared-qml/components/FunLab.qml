import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// The fun lab: a full-screen, scrollable sheet of easter-egg toys. Hosted by the
// settings page and the lyric chrome; every card is its own file so qml4j's
// one-constructor-per-document limit stays comfortable, and the whole sheet
// disappears when funLabEnabled is off.
Item {
    id: root
    property bool opened: false
    // The settings page sits under the app's mini player, so the sheet needs extra
    // scroll space for its last card to clear that bar.
    property real bottomInset: 0
    signal closeRequested()

    visible: settings.value("funLabEnabled") === true && (opened || opacity > 0.01)
    opacity: opened ? 1 : 0
    Behavior on opacity { NumberAnimation { duration: 180 } }

    Rectangle {
        anchors.fill: parent
        color: "#E60B0704"
    }

    Rectangle {
        id: sheet
        anchors.fill: parent

        Rectangle {
            id: header
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: 64
            color: "#1C120B"

            Text {
                anchors.left: parent.left
                anchors.leftMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                text: i18n.t("funlab.title")
                color: "#F2D7B0"
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: 22
                font.weight: Font.DemiBold
            }
            IconButton {
                anchors.right: parent.right
                anchors.rightMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                type: "standard"
                icon: "close"
                contentColor: "#E0B878"
                onClicked: root.closeRequested()
            }
        }

        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: header.bottom
            height: 1
            color: "#4E3420"
        }

        Flickable {
            id: flick
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: header.bottom
            anchors.bottom: parent.bottom
            clip: true
            contentWidth: width
            contentHeight: col.implicitHeight + 32 + root.bottomInset

            ColumnLayout {
                id: col
                width: Math.min(560, Math.max(0, flick.width - 24))
                x: (flick.width - width) / 2
                y: 16
                spacing: 14

                Text {
                    Layout.fillWidth: true
                    text: i18n.t("funlab.hint")
                    color: "#9C7A56"
                    font.pixelSize: 11
                    wrapMode: Text.WordWrap
                }

                FunVinylCard {
                    Layout.fillWidth: true
                    active: root.opened
                }
                FunTubeCard {
                    Layout.fillWidth: true
                    active: root.opened
                }
                FunVuCard {
                    Layout.fillWidth: true
                    active: root.opened
                }
                FunScopeCard {
                    Layout.fillWidth: true
                    active: root.opened
                }
                FunTapeCard {
                    Layout.fillWidth: true
                    active: root.opened
                }
                FunAbxCard { Layout.fillWidth: true; active: root.opened }
                FunGuessCard { Layout.fillWidth: true; active: root.opened }
                FunWheelCard { Layout.fillWidth: true; active: root.opened }
                FunRecapCard { Layout.fillWidth: true; active: root.opened }
                FunRadioCard { Layout.fillWidth: true; active: root.opened }
            }
        }
    }
}
