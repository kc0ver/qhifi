import QtQuick
import miuix.Core

// Full-bleed walnut texture for the retro skin.
//
// Painted directly above Main.qml's own fill and below every page, so pages use
// Theme.color.pageBackground (transparent in the wood scheme) and let this show
// through. Vector only: the bundled runtime ships no texture assets, and the
// grain has to survive being stretched to any window size.
Item {
    id: root
    visible: Theme.retroWood

    // Plank seams: a dark line with a lit edge below it reads as a joint rather
    // than a scratch.
    Repeater {
        model: 3
        delegate: Item {
            x: 0
            y: root.height * (0.22 + index * 0.27)
            width: root.width
            height: 3

            Rectangle {
                width: parent.width
                height: 1
                color: "#4D000000"
            }
            Rectangle {
                y: 1
                width: parent.width
                height: 1
                color: "#14FFE0B4"
            }
        }
    }

    // Fibres. Deterministic offsets keep the pattern stable across repaints
    // (a Math.random() grain would crawl every frame and burn CPU).
    Repeater {
        model: 54
        delegate: Rectangle {
            x: ((index * 71) % 23) - 12
            y: (index * 37 + 13) % Math.max(1, root.height)
            width: root.width - ((index * 29) % 40)
            height: index % 4 === 0 ? 2 : 1
            color: index % 3 === 0 ? "#1FFFD9A0" : "#260F0803"
            opacity: 0.35 + (index % 5) * 0.12
        }
    }

    // Cathedral figure: two soft bands that curve the eye across the plank.
    Rectangle {
        x: root.width * 0.08
        y: root.height * 0.38
        width: root.width * 0.62
        height: 3
        radius: 2
        color: "#22FFE2B4"
    }
    Rectangle {
        x: root.width * 0.30
        y: root.height * 0.72
        width: root.width * 0.58
        height: 2
        radius: 1
        color: "#2A000000"
    }

    // Lacquer falloff at the top and bottom edges so the sheet reads as a solid
    // cabinet panel instead of a flat fill.
    Rectangle {
        width: parent.width
        height: Math.round(parent.height * 0.16)
        gradient: Gradient {
            orientation: Gradient.Vertical
            GradientStop { position: 0.0; color: "#33000000" }
            GradientStop { position: 1.0; color: "#00000000" }
        }
    }
    Rectangle {
        y: parent.height - height
        width: parent.width
        height: Math.round(parent.height * 0.20)
        gradient: Gradient {
            orientation: Gradient.Vertical
            GradientStop { position: 0.0; color: "#00000000" }
            GradientStop { position: 1.0; color: "#40000000" }
        }
    }

    // Single warm highlight down the left, like light raking across a finished
    // board.
    Rectangle {
        x: parent.width * 0.06
        width: Math.max(24, parent.width * 0.10)
        height: parent.height
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0.0; color: "#00FFEBC8" }
            GradientStop { position: 0.5; color: "#12FFEBC8" }
            GradientStop { position: 1.0; color: "#00FFEBC8" }
        }
    }
}
