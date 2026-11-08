import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: blind A/B against the tube coloration. One of the two slots randomly
// carries the tube DSP; listening simply writes the real setting, so this is an
// honest A/B on the exclusive path (and inaudible elsewhere).
Rectangle {
    id: card
    property bool active: true

    property bool roundActive: false
    property int tubeSide: 0
    property bool answered: false
    property bool wasCorrect: false
    property int correct: 0
    property int total: 0
    property bool originalTube: false

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    function startRound() {
        if (card.roundActive) card.restore()
        card.originalTube = settings.value("hifiTubeTone") === true
        card.tubeSide = Math.random() < 0.5 ? 0 : 1
        card.roundActive = true
        card.answered = false
        card.wasCorrect = false
        card.listenA()
    }

    function listenA() {
        if (!card.roundActive) return
        settings.setValue("hifiTubeTone", card.tubeSide === 0)
    }

    function listenB() {
        if (!card.roundActive) return
        settings.setValue("hifiTubeTone", card.tubeSide === 1)
    }

    function pick(side) {
        if (!card.roundActive || card.answered) return
        card.answered = true
        card.total = card.total + 1
        card.wasCorrect = side === card.tubeSide
        if (card.wasCorrect) card.correct = card.correct + 1
        card.restore()
    }

    function restore() {
        if (!card.roundActive) return
        settings.setValue("hifiTubeTone", card.originalTube)
        card.roundActive = false
    }

    onActiveChanged: if (!card.active) card.restore()

    ColumnLayout {
        id: col
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 16
        spacing: 10

        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.abx.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.abx.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }
        Text {
            Layout.fillWidth: true
            visible: player.hifiVolumeKind === ""
            text: i18n.t("funlab.abx.notExclusive")
            color: "#D07A4A"
            font.pixelSize: 11
            wrapMode: Text.WordWrap
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Button {
                Layout.fillWidth: true
                type: "filledTonal"
                text: i18n.t("funlab.abx.start")
                onClicked: card.startRound()
            }
            Button {
                Layout.fillWidth: true
                type: "outlined"
                text: i18n.t("funlab.abx.listenA")
                enabled: card.roundActive
                onClicked: card.listenA()
            }
            Button {
                Layout.fillWidth: true
                type: "outlined"
                text: i18n.t("funlab.abx.listenB")
                enabled: card.roundActive
                onClicked: card.listenB()
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            visible: card.roundActive
            Button {
                Layout.fillWidth: true
                type: "outlined"
                text: i18n.t("funlab.abx.pickA")
                enabled: !card.answered
                onClicked: card.pick(0)
            }
            Button {
                Layout.fillWidth: true
                type: "outlined"
                text: i18n.t("funlab.abx.pickB")
                enabled: !card.answered
                onClicked: card.pick(1)
            }
        }

        Text {
            Layout.fillWidth: true
            visible: card.answered
            text: i18n.t(card.wasCorrect ? "funlab.abx.correct" : "funlab.abx.wrong")
            color: card.wasCorrect ? "#9ED08A" : "#D07A4A"
            font.pixelSize: 13
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.abx.score", card.correct, card.total)
            color: "#C9A45C"
            font.pixelSize: 12
        }
    }
}
