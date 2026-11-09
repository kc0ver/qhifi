import QtQuick

// Radio-skin nameplate: a walnut plank with brass edges behind the portrait
// title/artist block. The rest of the lyric page keeps its existing look — this
// only gives the two text lines a readable, in-world surface. Vector-only:
// layered gradients and deterministic fibre streaks, since the bundled runtime
// ships no texture assets.
Item {
    id: root

    property bool hostVisible: true
    readonly property bool enabledOnPage: settings.value("funLabEnabled") === true
                                         && settings.value("funRadioSkin") === true
    visible: enabledOnPage && hostVisible

    // Soft seat so the plank reads as furniture on the blurred cover, not a
    // flat cut-out pasted over it.
    Rectangle {
        anchors.fill: parent
        anchors.margins: -2
        radius: 10
        color: "#59000000"
    }

    Rectangle {
        id: board
        anchors.fill: parent
        radius: 8
        clip: true
        border.width: 1
        border.color: "#9A6632"

        gradient: Gradient {
            orientation: Gradient.Vertical
            GradientStop { position: 0.00; color: "#452714" }
            GradientStop { position: 0.12; color: "#6B3E1E" }
            GradientStop { position: 0.30; color: "#7B4A25" }
            GradientStop { position: 0.50; color: "#63391D" }
            GradientStop { position: 0.72; color: "#512F18" }
            GradientStop { position: 1.00; color: "#381F10" }
        }

        // Grain fibres: uneven lengths, heights and offsets, alternating
        // lighter/darker so the plank reads as walnut rather than a brown bar.
        Repeater {
            model: 20
            delegate: Rectangle {
                x: 4 + ((index * 17) % 7)
                y: 3 + ((index * 37 + 11) % 94) / 94 * (board.height - 6)
                width: board.width - 8 - ((index * 13) % 12)
                height: 1 + (index % 3)
                color: index % 3 === 0 ? "#33FFD9A0" : "#2E140B03"
                opacity: 0.4 + (index % 4) * 0.14
            }
        }

        // A couple of wider, softer bands: the "cathedral" figure that makes
        // real wood grain read from a distance.
        Rectangle {
            x: board.width * 0.18
            y: board.height * 0.34
            width: board.width * 0.44
            height: 2
            radius: 1
            color: "#24FFE2B4"
        }
        Rectangle {
            x: board.width * 0.36
            y: board.height * 0.66
            width: board.width * 0.52
            height: 2
            radius: 1
            color: "#30000000"
        }

        // Lacquer gloss across the top edge.
        Rectangle {
            width: parent.width
            height: Math.round(parent.height * 0.42)
            radius: 8
            gradient: Gradient {
                orientation: Gradient.Vertical
                GradientStop { position: 0.0; color: "#2EFFFFFF" }
                GradientStop { position: 1.0; color: "#00FFFFFF" }
            }
        }

        // Inner brass rule, matching the radio cabinet's frame.
        Rectangle {
            anchors.fill: parent
            anchors.margins: 3
            radius: 6
            color: "transparent"
            border.width: 1
            border.color: "#4A2E15"
        }
    }
}
