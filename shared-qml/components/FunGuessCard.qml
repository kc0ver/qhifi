import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Fun lab: guess-the-song. Starts a queue (or library) track at random and shows
// four titles; the answer is checked against the title captured at start time.
Rectangle {
    id: card
    property bool active: true

    property var options: []
    property string targetTitle: ""
    property bool answered: false
    property string picked: ""
    property int streak: 0
    property int rounds: 0
    property string message: ""
    property bool fromQueue: true

    radius: 20
    color: "#241810"
    border.width: 1
    border.color: "#5B3A24"
    implicitHeight: col.implicitHeight + 32

    function startRound() {
        var queue = player.queueTracks || []
        card.fromQueue = queue.length >= 4
        var list = card.fromQueue ? queue : (player.tracks || [])
        if (list.length < 4) {
            card.message = i18n.t("funlab.guess.needQueue")
            return
        }
        card.message = ""
        var target = Math.floor(Math.random() * list.length)
        card.targetTitle = list[target].title || "?"
        var opts = [card.targetTitle]
        var guard = 0
        while (opts.length < 4 && guard < 60) {
            guard++
            var title = list[Math.floor(Math.random() * list.length)].title || "?"
            if (opts.indexOf(title) < 0) opts.push(title)
        }
        for (var i = opts.length - 1; i > 0; i--) {
            var j = Math.floor(Math.random() * (i + 1))
            var tmp = opts[i]
            opts[i] = opts[j]
            opts[j] = tmp
        }
        card.options = opts
        card.answered = false
        card.picked = ""
        if (card.fromQueue) player.playQueueIndex(target)
        else player.play(target)
    }

    function answer(title) {
        if (card.answered || card.options.length === 0) return
        card.answered = true
        card.picked = title
        card.rounds = card.rounds + 1
        if (title === card.targetTitle) card.streak = card.streak + 1
        else card.streak = 0
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
            text: i18n.t("funlab.guess.title")
            color: "#F2D7B0"
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }
        Text {
            Layout.fillWidth: true
            text: i18n.t("funlab.guess.desc")
            color: "#B99B7C"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }
        Text {
            Layout.fillWidth: true
            visible: card.message.length > 0
            text: card.message
            color: "#D07A4A"
            font.pixelSize: 12
        }

        Repeater {
            model: card.options
            delegate: Button {
                Layout.fillWidth: true
                type: !card.answered ? "outlined"
                      : modelData === card.targetTitle ? "filledTonal" : "text"
                text: modelData
                onClicked: card.answer(modelData)
            }
        }

        Text {
            Layout.fillWidth: true
            visible: card.answered
            text: i18n.t(card.picked === card.targetTitle
                         ? "funlab.guess.correct" : "funlab.guess.wrong")
                  + " · " + card.targetTitle
            color: card.picked === card.targetTitle ? "#9ED08A" : "#D07A4A"
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Button {
                Layout.fillWidth: true
                type: "filledTonal"
                text: card.answered ? i18n.t("funlab.guess.next")
                                    : i18n.t("funlab.guess.start")
                onClicked: card.startRound()
            }
            Text {
                text: i18n.t("funlab.guess.score", card.streak, card.rounds)
                color: "#C9A45C"
                font.pixelSize: 12
            }
        }
    }
}
