import QtQuick
import QtQuick.Effects
import miuix.Core
Item {
    id: cardRoot

    property string type: "filled"
    property real radius: Theme.metrics.cardRadius
    property real padding: 0
    default property alias contentItem: contentContainer.data
    property alias color: cardRoot.containerColor
    property bool hovered: false
    property bool pressed: false
    property color containerColor: Theme.color.surfaceContainer
    property color outlineColor: Theme.color.outline
    property color rippleColor: "transparent"
    property color stateLayerColor: "transparent"
    property int elevationLevel: 0
    signal clicked()

    implicitWidth: 300
    implicitHeight: 200

    layer.enabled: true
    layer.effect: MultiEffect {
        maskEnabled: true
        maskSource: maskRect
    }

    Rectangle {
        id: maskRect
        anchors.fill: parent
        radius: cardRoot.radius
        color: "#000000"
        visible: false
    }

    Rectangle {
        anchors.fill: parent
        radius: cardRoot.radius
        color: cardRoot.containerColor
        border.width: cardRoot.type === "outlined" || Theme.retroWood ? 1 : 0
        border.color: cardRoot.outlineColor
    }

    // Retro skin: a lit top edge and a dark bottom edge turn the flat fill into
    // a raised panel. Both are inert decoration above the background.
    Rectangle {
        anchors.fill: parent
        radius: cardRoot.radius
        visible: Theme.retroWood
        gradient: Gradient {
            orientation: Gradient.Vertical
            GradientStop { position: 0.0; color: "#1FFFE4BC" }
            GradientStop { position: 0.12; color: "#00FFE4BC" }
            GradientStop { position: 0.88; color: "#00000000" }
            GradientStop { position: 1.0; color: "#33000000" }
        }
    }

    Item {
        id: contentContainer
        anchors.fill: parent
        anchors.margins: cardRoot.padding
    }
}
