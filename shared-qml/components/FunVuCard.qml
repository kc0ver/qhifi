import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: the mechanical VU pair, plus the switch that pins the same meters to
// the lyric page. Levels are a demo curve, not the real signal.
Rectangle {
    id: card
    property bool active: true

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    property real levelA: 0
    property real levelB: 0

    ColumnLayout {
        id: col
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.vu.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.vu.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 4
            spacing: 12
            Layout.alignment: Qt.AlignHCenter

            FunVuMeter {
                Layout.preferredWidth: 120
                Layout.preferredHeight: 128
                level: card.levelA
                label: "L"
            }
            FunVuMeter {
                Layout.preferredWidth: 120
                Layout.preferredHeight: 128
                level: card.levelB
                label: "R"
            }
        }

        SuperSwitch {
            Layout.fillWidth: true
            title: i18n.t("funlab.vu.switch")
            checked: settings.value("funVuMeter") === true
            property bool stored: settings.value("funVuMeter") === true
            onStoredChanged: checked = stored
            onClicked: settings.setValue("funVuMeter", checked)
        }
    }

    Timer {
        interval: 70
        repeat: true
        running: card.active
        onTriggered: {
            var t = Date.now() / 1000
            var a = player.playing
                    ? 0.18 + 0.42 * Math.abs(Math.sin(t * 1.4)) + 0.16 * Math.random()
                    : 0
            var b = player.playing
                    ? 0.16 + 0.40 * Math.abs(Math.sin(t * 1.1 + 1.2)) + 0.16 * Math.random()
                    : 0
            card.levelA = card.levelA * 0.5 + a * 0.5
            card.levelB = card.levelB * 0.5 + b * 0.5
        }
    }
}
