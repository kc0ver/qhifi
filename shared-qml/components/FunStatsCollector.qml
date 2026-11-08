import QtQuick

// Invisible listening-history collector for the fun lab's recap card. Lives in
// the app root so it runs no matter which page is open; writes its JSON into the
// hidden funStats setting (Android only) a few times a minute.
Item {
    id: collector
    visible: false
    width: 0
    height: 0

    property var stats: null
    property string stored: settings.value("funStats") || ""
    property string titleWatch: player.title

    Component.onCompleted: collector.load()

    function load() {
        var raw = collector.stored
        var parsed = null
        if (raw.length > 0) {
            try { parsed = JSON.parse(raw) } catch (e) { parsed = null }
        }
        if (!parsed) parsed = { totalSec: 0, nightSec: 0, plays: {} }
        if (!parsed.plays) parsed.plays = {}
        if (!parsed.totalSec) parsed.totalSec = 0
        if (!parsed.nightSec) parsed.nightSec = 0
        collector.stats = parsed
    }

    function commit() {
        if (!settings.has("funStats") || !collector.stats) return
        settings.setValue("funStats", JSON.stringify(collector.stats))
    }

    // The recap card's reset writes an empty string; drop the in-memory copy so
    // the next commit cannot resurrect the old numbers.
    onStoredChanged: {
        if (collector.stored.length === 0 && collector.stats && collector.stats.totalSec > 0)
            collector.load()
    }

    onTitleWatchChanged: {
        if (settings.value("funLabEnabled") !== true) return
        if (collector.titleWatch.length === 0 || !player.playing) return
        if (!collector.stats) collector.load()
        var key = collector.titleWatch
        collector.stats.plays[key] = (collector.stats.plays[key] || 0) + 1
        collector.commit()
    }

    Timer {
        interval: 15000
        repeat: true
        running: settings.value("funLabEnabled") === true && player.playing
        onTriggered: {
            if (!collector.stats) collector.load()
            var hour = new Date().getHours()
            collector.stats.totalSec += 15
            if (hour >= 23 || hour < 6) collector.stats.nightSec += 15
            collector.commit()
        }
    }
}
