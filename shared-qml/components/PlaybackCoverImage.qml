import QtQuick
import "."

// CoverImage wrapper with AMLL React Full's playback-state motion: pause shrinks the
// artwork to 75%, while resume restores it with a small overshoot. qml4j does
// not expose QML's BezierSpline easing, so a linear clock is mapped through the
// original CSS cubic-bezier curves explicitly. This intentionally uses composition
// rather than inheriting CoverImage: qml4j does not reliably instantiate the visual
// children of a custom QML type derived from another custom QML type.
//
// Vinyl style (settings.lyricCoverStyle): a black disc around a circular label,
// spinning while playing. Pause holds the current angle instead of shrinking —
// a record doesn't scale down when the needle lifts.
Item {
    id: cover

    property alias source: image.source
    property alias icon: image.icon
    property alias iconSize: image.iconSize
    property alias fadeIn: image.fadeIn
    property real radius: 8

    readonly property bool vinyl: settings.value("lyricCoverStyle") === 1
    // Vertical squash for the turntable view: a flat record seen from the front
    // is an ellipse, so the deck and the spinning disc share this projection.
    readonly property real tiltFactor: 0.62

    property bool playing: true
    // Item.visible is not ancestor-effective in qml4j.
    property bool animationActive: true
    property real baseScale: 1.0
    property real pauseShrinkAspect: cover.vinyl ? 1.0 : 0.75

    property real playbackScale: playing ? 1.0 : pauseShrinkAspect
    property real motionProgress: 1.0
    property real motionFrom: playbackScale
    property real motionTo: playbackScale
    property bool motionResuming: playing
    property bool motionReady: false

    scale: baseScale * playbackScale

    onVinylChanged: {
        if (cover.vinyl) {
            playbackScale = 1.0
        } else {
            disc.rotation = 0
            playbackScale = playing ? 1.0 : 0.75
        }
    }

    // Turntable deck under the record: a dark platter plate squashed by the same
    // projection as the disc, plus a walnut plinth face below it. Draw order is
    // deck → record shadow → record.
    Item {
        id: deck
        visible: cover.vinyl
        anchors.fill: parent

        Item {
            id: platter
            width: cover.width * 1.16
            height: width
            x: (cover.width - width) / 2
            y: (cover.height - height) / 2
            transform: Scale {
                yScale: cover.tiltFactor
                origin.x: platter.width / 2
                origin.y: platter.height / 2
            }

            Rectangle {
                anchors.fill: parent
                radius: width / 2
                gradient: Gradient {
                    orientation: Gradient.Vertical
                    GradientStop { position: 0.0; color: "#33241A" }
                    GradientStop { position: 0.52; color: "#1C130D" }
                    GradientStop { position: 1.0; color: "#0E0906" }
                }
                border.width: 1
                border.color: "#5C3E24"
            }

            Rectangle {
                width: parent.width * 0.9
                height: width
                anchors.centerIn: parent
                radius: width / 2
                color: "transparent"
                border.width: 1
                border.color: "#332419"
            }
        }

        // Plinth front face. The platter's lower rim overlaps it by a few px so
        // the record reads as sitting in the deck, not floating above a bar.
        Rectangle {
            width: cover.width * 1.16
            height: cover.width * 0.105
            x: (cover.width - width) / 2
            y: cover.height / 2 + cover.width * 1.16 * cover.tiltFactor / 2
               - cover.width * 0.012
            radius: 4
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0.0; color: "#341D10" }
                GradientStop { position: 0.2; color: "#6B3E1E" }
                GradientStop { position: 0.5; color: "#7C4A24" }
                GradientStop { position: 0.8; color: "#5A3418" }
                GradientStop { position: 1.0; color: "#2E190D" }
            }
            border.width: 1
            border.color: "#8A5A2E"

            Rectangle {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.topMargin: 1
                height: 1
                color: "#D8B26A"
                opacity: 0.6
            }
        }
    }

    // Soft elliptical drop on the deck. The lyric page's MultiEffect shadow is
    // a rectangular saveLayer and looks wrong around a round record, so vinyl
    // draws this instead and the page turns that effect off.
    Rectangle {
        id: vinylShadow
        objectName: "playbackCoverVinylShadow"
        visible: cover.vinyl
        width: cover.width + 10
        height: width
        x: (cover.width - width) / 2
        y: (cover.height - height) / 2 + 8
        radius: width / 2
        color: "#66000000"
        transform: Scale {
            yScale: cover.tiltFactor
            origin.x: vinylShadow.width / 2
            origin.y: vinylShadow.height / 2
        }
    }

    // The projector squashes the whole record vertically; the disc inside keeps
    // only its own in-plane rotation, so it spins as a record instead of
    // tumbling with the projection.
    Item {
        id: projector
        anchors.fill: parent
        transform: Scale {
            yScale: cover.vinyl ? cover.tiltFactor : 1.0
            origin.x: projector.width / 2
            origin.y: projector.height / 2
        }

        Item {
            id: disc
            objectName: "playbackCoverDisc"
            width: cover.width
            height: cover.height

        Rectangle {
            objectName: "playbackCoverVinyl"
            visible: cover.vinyl
            width: disc.width
            height: disc.height
            radius: width / 2
            color: "#111111"
            border.width: Math.max(2, disc.width * 0.018)
            border.color: "#2A2A2A"
        }

        Rectangle {
            visible: cover.vinyl
            width: disc.width * 0.92
            height: width
            x: (disc.width - width) / 2
            y: (disc.height - height) / 2
            radius: width / 2
            color: "transparent"
            border.width: 1
            border.color: "#1A1A1A"
        }
        Rectangle {
            visible: cover.vinyl
            width: disc.width * 0.84
            height: width
            x: (disc.width - width) / 2
            y: (disc.height - height) / 2
            radius: width / 2
            color: "transparent"
            border.width: 1
            border.color: "#1A1A1A"
        }
        Rectangle {
            visible: cover.vinyl
            width: disc.width * 0.76
            height: width
            x: (disc.width - width) / 2
            y: (disc.height - height) / 2
            radius: width / 2
            color: "transparent"
            border.width: 1
            border.color: "#1A1A1A"
        }

        CoverImage {
            id: image
            objectName: "playbackCoverArt"
            width: cover.vinyl ? disc.width * 0.64 : disc.width
            height: width
            x: (disc.width - width) / 2
            y: (disc.height - height) / 2
            radius: cover.vinyl ? width / 2 : cover.radius
        }

        Rectangle {
            visible: cover.vinyl
            width: image.width + 2
            height: width
            x: (disc.width - width) / 2
            y: (disc.height - height) / 2
            radius: width / 2
            color: "transparent"
            border.width: 1
            border.color: "#33FFFFFF"
        }

        Rectangle {
            objectName: "playbackCoverSpindle"
            visible: cover.vinyl
            width: Math.max(8, disc.width * 0.055)
            height: width
            x: (disc.width - width) / 2
            y: (disc.height - height) / 2
            radius: width / 2
            color: "#0A0A0A"
            border.width: 1
            border.color: "#3A3A3A"
        }
    }
    }

    Timer {
        interval: 32
        repeat: true
        running: cover.animationActive && cover.vinyl && cover.playing && cover.visible
        onTriggered: disc.rotation = (disc.rotation + 0.6) % 360
    }

    function bezierCoordinate(t, p1, p2) {
        var oneMinusT = 1.0 - t
        return 3.0 * oneMinusT * oneMinusT * t * p1
                + 3.0 * oneMinusT * t * t * p2 + t * t * t
    }

    function amllTiming(progress, resuming) {
        if (progress <= 0.0) return 0.0
        if (progress >= 1.0) return 1.0

        var x1 = resuming ? 0.3 : 0.4
        var y1 = 0.2
        var x2 = resuming ? 0.2 : 0.1
        var y2 = resuming ? 1.4 : 1.0
        var low = 0.0
        var high = 1.0
        var t = progress
        // CSS cubic-bezier timing is y(t) at the t whose x(t) equals the linear
        // clock. Bisection is stable for both AMLL curves and plenty accurate at
        // display refresh rates.
        for (var i = 0; i < 14; i++) {
            t = (low + high) * 0.5
            if (bezierCoordinate(t, x1, x2) < progress)
                low = t
            else
                high = t
        }
        return bezierCoordinate((low + high) * 0.5, y1, y2)
    }

    function animatePlaybackState() {
        if (cover.vinyl) {
            playbackScale = 1.0
            return
        }
        motionAnim.stop()
        motionFrom = playbackScale
        motionTo = playing ? 1.0 : pauseShrinkAspect
        motionResuming = playing
        motionProgress = 0.0
        motionAnim.duration = playing ? 500 : 600
        motionAnim.restart()
    }

    onPlayingChanged: {
        if (motionReady)
            animatePlaybackState()
    }

    onMotionProgressChanged: {
        playbackScale = motionFrom
                + (motionTo - motionFrom) * amllTiming(motionProgress, motionResuming)
    }

    Component.onCompleted: {
        playbackScale = (cover.vinyl || playing) ? 1.0 : pauseShrinkAspect
        motionReady = true
    }

    NumberAnimation {
        id: motionAnim
        target: cover
        property: "motionProgress"
        from: 0.0
        to: 1.0
        duration: 500
        easing.type: Easing.Linear
    }
}
