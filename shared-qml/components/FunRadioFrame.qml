import QtQuick

// Vintage-radio cabinet around the lyric page: thin wooden edges, a brass
// nameplate at the bottom and two corner screws. Purely decorative.
Item {
    id: root
    z: 0

    property bool hostOpen: player.lyricSlide > 0.001
    property bool enabledOnPage: settings.value("funLabEnabled") === true
                                 && settings.value("funRadioSkin") === true
    visible: enabledOnPage && hostOpen
    anchors.fill: parent

    Rectangle {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 10
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0.0; color: "#2A160C" }
            GradientStop { position: 0.2; color: "#5B3218" }
            GradientStop { position: 0.5; color: "#7A4522" }
            GradientStop { position: 0.8; color: "#5B3218" }
            GradientStop { position: 1.0; color: "#2A160C" }
        }
    }

    Rectangle {
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        height: 10
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0.0; color: "#2A160C" }
            GradientStop { position: 0.2; color: "#5B3218" }
            GradientStop { position: 0.5; color: "#7A4522" }
            GradientStop { position: 0.8; color: "#5B3218" }
            GradientStop { position: 1.0; color: "#2A160C" }
        }
    }

    Rectangle {
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: 8
        gradient: Gradient {
            orientation: Gradient.Vertical
            GradientStop { position: 0.0; color: "#2A160C" }
            GradientStop { position: 0.5; color: "#6B3C1C" }
            GradientStop { position: 1.0; color: "#2A160C" }
        }
    }

    Rectangle {
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: 8
        gradient: Gradient {
            orientation: Gradient.Vertical
            GradientStop { position: 0.0; color: "#2A160C" }
            GradientStop { position: 0.5; color: "#6B3C1C" }
            GradientStop { position: 1.0; color: "#2A160C" }
        }
    }

    // Brass plate, bottom centre, just above the transport band.
    Rectangle {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: settings.bottomInset + 132
        width: 96
        height: 18
        radius: 4
        color: "#8A6A32"
        border.width: 1
        border.color: "#C9A45C"
        Text {
            anchors.centerIn: parent
            text: "QHIFI RADIO"
            color: "#F4DC9A"
            fontSize: 8
            font.weight: Font.DemiBold
        }
    }

    Rectangle {
        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.leftMargin: 4
        anchors.bottomMargin: 4
        width: 5
        height: 5
        radius: 2.5
        color: "#D8B26A"
    }
    Rectangle {
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: 4
        anchors.bottomMargin: 4
        width: 5
        height: 5
        radius: 2.5
        color: "#D8B26A"
    }
}
