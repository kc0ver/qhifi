import QtQuick
import miuix.Core
import "../components"

BasicComponent {
    id: row
    property var spec: null
    property string providerText: spec && spec.provider.length > 0 ? settings.info(spec.provider) : ""
    title: spec ? i18n.t(spec.title) : ""
    summary: {
        if (!row.spec) return ""
        var desc = i18n.t(row.spec.desc)
        return !row.spec.inlineProvider && row.providerText.length > 0
            ? row.providerText + (desc.length > 0 ? "\n" + desc : "") : desc
    }
    property string rightText: spec && spec.inlineProvider ? providerText : (spec ? i18n.t(spec.button) : "")
    startAction: Component {
        Icon {
            objectName: "settingActionLeadingIcon"
            name: row.spec ? row.spec.icon : ""
            implicitWidth: name.length > 0 ? 24 : 0
            implicitHeight: implicitWidth
            width: implicitWidth
            height: implicitHeight
            color: Theme.color.onSurfaceVariantColor
        }
    }
    endAction: Component {
        Item {
            id: endItem
            property real chevronWidth: 10
            property real gap: row.rightText.length > 0 ? 8 : 0
            property real capWidth: Math.max(0, (row.width - row.horizontalPadding * 2) * 0.4)
            property real naturalWidth: probe.implicitWidth + chevronWidth + gap
            implicitWidth: Math.min(naturalWidth, capWidth)
            implicitHeight: Math.max(probe.implicitHeight, 16)
            height: implicitHeight

            // Hidden probe gives the stable natural width; the marquee itself
            // is explicitly sized below, so no width binding can collapse into
            // a measure loop (the previous elide attempt did exactly that).
            Text {
                id: probe
                visible: false
                text: row.rightText
                font.pixelSize: 14
            }
            MarqueeText {
                width: Math.max(0, endItem.width - endItem.chevronWidth - endItem.gap)
                text: row.rightText
                textColor: Theme.color.onSurfaceVariantActions
                fontSize: 14
                anchors.verticalCenter: parent.verticalCenter
            }
            Icon {
                id: chevron
                name: "chevron_right"
                x: endItem.width - width
                width: endItem.chevronWidth; height: 16
                anchors.verticalCenter: parent.verticalCenter
                color: Theme.color.onSurfaceVariantActions
            }
        }
    }
    onClicked: if (spec) settings.invoke(spec.action)
}
