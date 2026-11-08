import QtQuick
import "."

// Bundles the lyric-page decorations into one node so LyricOverlay.qml only gains
// a single instance (its constructor is already at the qml4j 64KB ceiling). Each
// child keeps its own visibility switch from the fun lab.
Item {
    id: root

    FunRadioFrame { objectName: "lyricFunRadioFrame" }
    FunTubeGlow { objectName: "lyricFunTubeGlow" }
    FunVuOverlay { objectName: "lyricFunVuOverlay" }
}
