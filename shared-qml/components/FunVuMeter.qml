import QtQuick

// One analogue VU movement: a fan of ticks and a needle pivoting from the bottom
// edge, with a springy return. The needle angle is driven by `level` (0..1).
Item {
    id: meter

    property real level: 0
    property string label: "VU"

    readonly property real pivotX: width / 2
    readonly property real pivotY: height - 12
    property real angle: -48 + Math.max(0, Math.min(1, level)) * 96

    Behavior on angle {
        NumberAnimation {
            duration: 150
            easing.type: Easing.OutBack
        }
    }

    Rectangle {
        anchors.fill: parent
        radius: 10
        color: "#17110C"
        border.width: 1
        border.color: "#5E4028"

        Text {
            anchors.top: parent.top
            anchors.topMargin: 3
            anchors.horizontalCenter: parent.horizontalCenter
            text: meter.label
            color: "#C98A3E"
            fontSize: 9
            font.weight: Font.DemiBold
        }

        // Scale ticks fan out from the needle pivot.
        Repeater {
            model: 11
            delegate: Rectangle {
                x: meter.pivotX - 0.5
                y: meter.pivotY - 38
                width: 1
                height: index === 0 || index === 10 ? 7 : 4
                color: index > 8 ? "#D05A32" : "#8C6A45"
                transformOrigin: Item.Bottom
                rotation: -48 + index * 9.6
            }
        }

        Item {
            x: 0
            y: 0
            width: parent.width
            height: parent.height

            Rectangle {
                x: meter.pivotX - 0.75
                y: meter.pivotY - 34
                width: 1.5
                height: 34
                color: "#F2B45A"
                transformOrigin: Item.Bottom
                rotation: meter.angle
            }
        }

        Rectangle {
            width: 6
            height: 6
            radius: 3
            x: meter.pivotX - 3
            y: meter.pivotY - 3
            color: "#E0A24A"
        }

        Rectangle {
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 1
            anchors.horizontalCenter: parent.horizontalCenter
            width: parent.width * 0.66
            height: 1
            color: "#3A2A1D"
        }
    }
}
