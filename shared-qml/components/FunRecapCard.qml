import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: listening recap built from the hidden funStats JSON that
// FunStatsCollector.qml keeps in the background.
Rectangle {
    id: card
    property bool active: true

    property string raw: settings.value("funStats") || ""
    property var stats: card.parse(card.raw)
    property var topTracks: card.topThree(card.stats)
    property int playCount: card.totalPlays(card.stats)

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    function parse(raw) {
        var out = null
        if (raw.length > 0) {
            try { out = JSON.parse(raw) } catch (e) { out = null }
        }
        return out || { totalSec: 0, nightSec: 0, plays: {} }
    }

    function totalPlays(s) {
        var n = 0
        var keys = Object.keys(s.plays || {})
        for (var i = 0; i < keys.length; i++) n += s.plays[keys[i]]
        return n
    }

    function topThree(s) {
        var keys = Object.keys(s.plays || {})
        var best = []
        for (var i = 0; i < keys.length; i++) {
            best.push({ title: keys[i], count: s.plays[keys[i]] })
        }
        best.sort(function (a, b) { return b.count - a.count })
        return best.slice(0, 3)
    }

    function durationText(sec) {
        var h = Math.floor(sec / 3600)
        var m = Math.floor((sec % 3600) / 60)
        return h + " " + i18n.t("funlab.recap.hours") + " " + m + " min"
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
            text: i18n.t("funlab.recap.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.recap.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        Text {
            Layout.fillWidth: true
            visible: (card.stats.totalSec || 0) <= 0
            text: i18n.t("funlab.recap.empty")
            color: "#C9A45C"
            font.pixelSize: 12
        }

        RowLayout {
            Layout.fillWidth: true
            visible: (card.stats.totalSec || 0) > 0
            spacing: 10
            ColumnLayout {
                Layout.fillWidth: true
                Text {
                    text: i18n.t("funlab.recap.total")
                    color: "#9C7A56"
                    font.pixelSize: 10
                }
                Text {
                    text: card.durationText(card.stats.totalSec || 0)
                    color: "#F2D7B0"
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
            }
            ColumnLayout {
                Layout.fillWidth: true
                Text {
                    text: i18n.t("funlab.recap.tracks")
                    color: "#9C7A56"
                    font.pixelSize: 10
                }
                Text {
                    text: "" + card.playCount
                    color: "#F2D7B0"
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
            }
            ColumnLayout {
                Layout.fillWidth: true
                Text {
                    text: i18n.t("funlab.recap.night")
                    color: "#9C7A56"
                    font.pixelSize: 10
                }
                Text {
                    text: Math.round((card.stats.nightSec || 0) * 100
                                     / Math.max(1, card.stats.totalSec || 1)) + "%"
                    color: "#F2D7B0"
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
            }
        }

        Text {
            Layout.fillWidth: true
            visible: card.topTracks.length > 0
            text: i18n.t("funlab.recap.top")
            color: "#C9A45C"
            font.pixelSize: 12
            font.weight: Font.DemiBold
        }
        Repeater {
            model: card.topTracks
            delegate: RowLayout {
                Layout.fillWidth: true
                Text {
                    Layout.fillWidth: true
                    text: modelData.title
                    color: "#D9B98C"
                    font.pixelSize: 12
                    elide: Text.ElideRight
                }
                Text {
                    text: "× " + modelData.count
                    color: "#9C7A56"
                    font.pixelSize: 12
                }
            }
        }

        Button {
            Layout.fillWidth: true
            type: "text"
            text: i18n.t("funlab.reset")
            onClicked: settings.setValue("funStats", "")
        }
    }
}
