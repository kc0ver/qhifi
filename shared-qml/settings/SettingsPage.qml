import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../dialogs"
import "../components"

// App settings overlay. Nothing here knows what a setting IS: the categories and
// the rows come from player-core's SettingsCatalog through the `settings` context
// global (SettingsCore), and each row is rendered by whichever Setting*Row
// component matches its declared type. Adding a setting is a catalog entry —
// no edit here, and none in either platform's host code.
//
// This also keeps the page well clear of the 64KB-per-QML-file constructor limit
// that forced the old hand-written version to be split across six files: the
// markup is now one Repeater plus one Component per row type.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    signal home()
    signal openDebugLog()
    color: Theme.color.surface

    property var categories: settings.categories()
    property var categoryTabModel: {
        var out = []
        for (var i = 0; i < page.categories.length; i++)
            out.push(i18n.t("settings.category." + page.categories[i]))
        return out
    }

    // Category switching uses Main.qml's fade-through verbatim (fade out,
    // swap, fade back in while rising), so it reads the same as switching pages.
    property string currentCategory: page.categories.length > 0 ? page.categories[0] : ""
    property string nextCategory: page.currentCategory
    property real panelOpacity: 1
    property real panelShift: 0
    property var groups: settings.groups(page.currentCategory)
    property var installedPlugins: player.sourcePlugins || []
    property var availablePlugins: {
        var out = []
        var rows = player.pluginCatalogEntries || []
        for (var i = 0; i < rows.length; i++) {
            if (!rows[i].installed) out.push(rows[i])
        }
        return out
    }

    function selectCategory(name) {
        if (!name || name === page.currentCategory) return
        page.nextCategory = name
        categoryAnim.restart()
    }

    // HifiOutputMode's persisted index -> its label. Only two outcomes exist now
    // (see HifiOutputMode): 0 system, 1 the app's userspace USB exclusive driver.
    function hifiModeLabel(id) {
        return i18n.t(id === 1 ? "settings.hifi.mode.exclusive"
                    : "settings.hifi.mode.system")
    }

    function hifiExclusiveLabel(state) {
        return i18n.t(state === "full" ? "settings.hifiChain.exclusive.full"
                    : state === "partial" ? "settings.hifiChain.exclusive.partial"
                    : "settings.hifiChain.exclusive.none")
    }

    SequentialAnimation {
        id: categoryAnim
        NumberAnimation {
            target: page; property: "panelOpacity"; to: 0
            duration: 90; easing.type: Easing.OutCubic
        }
        ScriptAction {
            onTrigger: {
                page.currentCategory = page.nextCategory
                settingsFlickable.contentY = 0
                page.panelShift = 28
            }
        }
        ParallelAnimation {
            NumberAnimation {
                target: page; property: "panelOpacity"; from: 0; to: 1
                duration: 220; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: page; property: "panelShift"; from: 28; to: 0
                duration: 220; easing.type: Easing.OutCubic
            }
        }
    }

    // Catch-all so taps on empty areas don't fall through to the page beneath.
    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            PageHeaderButtons {
                Layout.alignment: Qt.AlignVCenter
                onHome: page.home()
                onBack: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: i18n.t("settings.title")
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: 28
                font.weight: Font.DemiBold
            }
            IconButton {
                objectName: "settingsDebugAction"
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                icon: "bug_report"
                onClicked: page.openDebugLog()
            }
        }

        TabRowWithContour {
            id: categoryTabs
            objectName: "settingsCategoryTabs"
            equalWidth: false
            Layout.fillWidth: true
            Layout.preferredHeight: 45
            Layout.leftMargin: 12
            Layout.rightMargin: 12
            tabs: page.categoryTabModel
            selectedTabIndex: page.categories.indexOf(page.currentCategory)
            selectOnClick: false
            onTabSelected: (index) => page.selectCategory(page.categories[index])
        }

        Flickable {
            id: settingsFlickable
            Layout.fillWidth: true
            Layout.fillHeight: true
            // Breathing room under the tab bar so the first card doesn't sit on
            // the indicator.
            Layout.topMargin: 16
            clip: true
            contentWidth: width
            contentHeight: groupsColumn.implicitHeight + 40 + page.bottomContentInset

            ColumnLayout {
                id: groupsColumn
                objectName: "settingsSingleColumn"
                width: Math.min(880, Math.max(0, settingsFlickable.width - 32))
                x: (settingsFlickable.width - width) / 2
                y: page.panelShift
                opacity: page.panelOpacity
                spacing: 20

                    SettingCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "about"
                                 && player.credentialOwnerOnlyFallback

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            SettingTitle { text: i18n.t("settings.credential.title") }
                            Button {
                                type: "filledTonal"
                                icon: "enhanced_encryption"
                                enabled: !player.credentialProtectionBusy
                                text: i18n.t(player.credentialProtectionBusy
                                             ? "settings.credential.busy"
                                             : "settings.credential.button")
                                onClicked: player.reenableSystemCredentialProtection()
                            }
                        }
                        SettingDesc {
                            text: i18n.t("settings.credential.desc")
                        }
                    }

                    Text {
                        Layout.fillWidth: true
                        Layout.leftMargin: 4
                        visible: page.currentCategory === "plugins"
                        text: i18n.t("plugin.installed")
                        color: Theme.color.primary
                        fontSize: 14
                        font.weight: Font.DemiBold
                    }

                    Card {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "plugins" && page.installedPlugins.length > 0
                        implicitHeight: pluginRows.implicitHeight
                        ColumnLayout {
                            id: pluginRows
                            width: parent.width
                            spacing: 0
                            Repeater {
                                model: page.currentCategory === "plugins" ? page.installedPlugins : null
                                PluginSettingsEntry { Layout.fillWidth: true; pluginData: modelData }
                            }
                        }
                    }

                    SettingCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "plugins"
                                 && page.installedPlugins.length === 0

                        SettingTitle { text: i18n.t("plugin.none.title") }
                        SettingDesc { text: i18n.t("plugin.none.desc") }
                    }

                    Text {
                        Layout.fillWidth: true
                        Layout.leftMargin: 4
                        visible: page.currentCategory === "plugins"
                                 && page.availablePlugins.length > 0
                        text: i18n.t("plugin.available")
                        color: Theme.color.primary
                        fontSize: 14
                        font.weight: Font.DemiBold
                    }

                    Repeater {
                        model: page.currentCategory === "plugins" ? page.availablePlugins : null
                        delegate: PluginCatalogEntry {
                            Layout.fillWidth: true
                            pluginData: modelData
                        }
                    }

                    SettingCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "plugins"
                                 && player.pluginCatalogLoading

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 10
                            LoadingIndicator {
                                running: player.pluginCatalogLoading
                            }
                            SettingDesc { text: i18n.t("plugin.catalog.loading") }
                        }
                    }

                    SettingCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "plugins"

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            Button {
                                type: "text"
                                icon: "refresh"
                                text: i18n.t("plugin.catalog.refresh")
                                enabled: !player.pluginCatalogLoading && !player.pluginInstallBusy
                                onClicked: player.refreshPluginCatalog()
                            }
                            Item { Layout.fillWidth: true }
                            Button {
                                type: "outlined"
                                icon: "upload_file"
                                text: i18n.t("plugin.catalog.import")
                                enabled: !player.pluginInstallBusy
                                onClicked: player.requestPluginImport()
                            }
                        }
                        SettingDesc {
                            text: i18n.t("plugin.catalog.desc")
                        }
                    }

                Repeater {
                    model: page.groups
                    SettingGroup {
                        Layout.fillWidth: true
                        groupData: modelData
                    }
                }

                // 音频链路 (HiFi tab): a read-out of what the audio path is
                // actually doing, not a setting — the rows above it are the
                // controls, this card explains their current result. Every value
                // is a player.hifi* Property, so it follows the backend without
                // anything here polling or wiring signals.
                SettingCard {
                    Layout.fillWidth: true
                    visible: page.currentCategory === "hifi"

                    SettingTitle { text: i18n.t("settings.hifiChain.title") }

                    // Manual refresh: the readout below is driven by the backend,
                    // but a DAC plugged in (or a grant just made) is not always
                    // noticed instantly — these two re-query on demand, one for the
                    // device list, one for the whole chain.
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        Button {
                            type: "text"
                            icon: "refresh"
                            text: i18n.t("settings.hifiChain.refreshDevices")
                            onClicked: player.refreshHifiDevices()
                        }
                        Button {
                            type: "text"
                            icon: "refresh"
                            text: i18n.t("settings.hifiChain.refresh")
                            onClicked: player.refreshHifiChain()
                        }
                        Item { Layout.fillWidth: true }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.mode.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: page.hifiModeLabel(player.hifiOutputMode)
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.effective.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            // Only the fallback case is worth spelling out; when the
                            // requested mode is the one running, the row above already
                            // says which.
                            text: player.hifiEffectiveMode === player.hifiOutputMode
                                  ? page.hifiModeLabel(player.hifiEffectiveMode)
                                  : i18n.t("settings.hifiChain.effective.fallback")
                                    + " · " + page.hifiModeLabel(player.hifiEffectiveMode)
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.exclusive.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: page.hifiExclusiveLabel(player.hifiExclusiveState)
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.source.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: player.hifiSourceFormat
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.output.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: player.hifiDeviceName
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        visible: player.hifiUacVersion !== ""
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.uac.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: player.hifiUacVersion
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.format.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: player.hifiOutputFormat
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.status.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: i18n.t(player.hifiResampled
                                         ? "settings.hifiChain.status.resampled"
                                         : "settings.hifiChain.status.direct")
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.capability.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: i18n.t(!player.hifiBitPerfectSupported
                                         ? "settings.hifiChain.capability.unsupported"
                                         : player.hifiBitPerfectActive
                                           ? "settings.hifiChain.capability.active"
                                           : "settings.hifiChain.capability.available")
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Text {
                            Layout.fillWidth: true
                            text: i18n.t("settings.hifiChain.capability.usb.title")
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                        Text {
                            text: i18n.t(player.hifiUsbDevicePresent
                                         ? "settings.hifiChain.capability.usb.present"
                                         : "settings.hifiChain.capability.usb.absent")
                            color: Theme.color.onSurfaceColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: 14
                        }
                    }

                    // The one row that is a sentence rather than a label/value
                    // pair: a wrapping line of its own, so it never has to fight
                    // the row layout for width. A fallback outranks the mode's own
                    // note — it is the more specific thing to say.
                    Text {
                        Layout.fillWidth: true
                        visible: player.hifiFallbackReason.length > 0
                                 || player.hifiReason.length > 0
                        text: i18n.t("settings.hifiChain.reason.title") + ": "
                              + (player.hifiFallbackReason.length > 0
                                 ? player.hifiFallbackReason : player.hifiReason)
                        color: Theme.color.onSurfaceVariantColor
                        font.pixelSize: 14
                        wrapMode: Text.WordWrap
                    }
                }
            }

            ViewportScrollBar {
                target: settingsFlickable
            }
        }
    }

    FontPickerDialog {
        active: settings.fontPickerOpen
        targetKey: settings.fontPickerTarget
        onClosed: settings.fontPickerOpen = false
    }

    ColorPickerDialog { objectName: "settingsColorPicker" }

}
