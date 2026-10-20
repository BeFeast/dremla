package com.befeast.sleepmusic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.befeast.sleepmusic.R
import com.befeast.sleepmusic.playback.*

/** State injection keeps the production scene and the golden scene identical. */
@Composable
fun NightScene(
    session: SessionState,
    status: CollectionStatus? = null,
    menuOpen: Boolean = false,
    onTimer: (Int) -> Unit = {},
    onTvOff: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onLike: () -> Unit = {},
    onNever: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onSource: (() -> Unit)? = null,
) {
    val type = rememberBrandType()
    Box(Modifier.fillMaxSize().background(BrandColors.Ground)) {
        if (session.phase == Phase.Finished || session.closeActivity || (session.blackScreen && (!session.online || !menuOpen))) return@Box
        if (menuOpen) {
            NightMenu(session, status, type, onTimer, onTvOff, onRefresh, onLike, onNever, onSource)
            Hints(listOf("↑ ↓ ← →" to "move", "OK" to "choose", "BACK" to "close"), type)
            return@Box
        }
        if (session.online && session.onlinePhase == "unavailable") {
            Column(Modifier.align(Alignment.Center)) {
                Label("Online playback unavailable", type.Headline)
                Label(if (session.playableCount == 0) "A valid catalog is needed. Open the menu to change source." else "Try a new launcher start. The sleep timer is still running.", type.Caption)
            }
            Hints(listOf("hold OK" to "menu"), type)
        } else if (status?.phase in listOf(CollectionPhase.Loading, CollectionPhase.Downloading, CollectionPhase.FirstRunError)) {
            DownloadScene(session.collectionLoaded, status!!, type, onRefresh, onOpenMenu)
            Hints(listOf("hold OK" to "menu"), type)
        } else if (session.track != null) {
            Column(Modifier.fillMaxSize().padding(start = BrandSpacing.ScreenX, end = BrandSpacing.ScreenX,
                bottom = BrandSpacing.ContentBottomReserve), verticalArrangement = Arrangement.Center) {
                Eyebrow(if (session.phase == Phase.Paused) "PAUSED" else if (session.onlinePhase in listOf("starting", "resuming")) "CONNECTING" else session.track.category.uppercase(), type)
                Spacer(Modifier.height(16.dp))
                BasicText(session.track.title, style = type.Title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = BrandSpacing.TitleMeasure))
                Spacer(Modifier.height(14.dp))
                Label(session.track.artist, type.Artist)
                Spacer(Modifier.height(36.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        remainingParts(session.timerRemainingSec).forEach { (number, unit) ->
                            Label(number.toString(), type.Time)
                            Label(unit, type.Time.copy(fontSize = 20.sp), Modifier.padding(start = 3.dp, end = 10.dp, bottom = 3.dp))
                        }
                    }
                    Label(if (session.tvOff) "until the music fades\nand the TV turns off" else "until the music fades", type.TimeLabel)
                }
            }
            Hints(listOf("OK" to if (session.phase == Phase.Paused) "resume" else "pause", "→" to "next", "hold OK" to "menu"), type)
        }
    }
}

@Composable private fun Label(text: String, style: TextStyle, modifier: Modifier = Modifier) = BasicText(text, modifier, style)
@Composable private fun Dot() = Box(Modifier.size(6.dp).background(BrandColors.Ember, CircleShape))
@Composable private fun Eyebrow(text: String, type: BrandType) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(); Label(text, type.Eyebrow)
    }
}

@Composable private fun BoxScope.Hints(hints: List<Pair<String, String>>, type: BrandType) {
    Row(Modifier.align(Alignment.BottomStart).padding(start = BrandSpacing.ScreenX, bottom = BrandSpacing.HintBottom),
        horizontalArrangement = Arrangement.spacedBy(BrandSpacing.HintGap), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { (keys, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (keys.startsWith("hold")) Label("hold", type.Hint, Modifier.padding(end = 6.dp))
                keys.removePrefix("hold ").split(' ').forEach { key ->
                    Box(Modifier.padding(end = 6.dp).height(BrandSpacing.KeycapHeight).widthIn(min = BrandSpacing.KeycapMinWidth)
                        .border(BrandSpacing.KeycapBorder, BrandColors.Key).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                        Label(key, type.Keycap)
                    }
                }
                Label(label, type.Hint)
            }
        }
    }
}

@Composable private fun BoxScope.DownloadScene(refresh: Boolean, status: CollectionStatus, type: BrandType, retry: () -> Unit, openMenu: () -> Unit) {
    val error = status.phase == CollectionPhase.FirstRunError
    val progress = status.progress
    Column(Modifier.fillMaxSize().padding(start = BrandSpacing.ScreenX, bottom = BrandSpacing.ContentBottomReserve),
        verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.mark_night), null, Modifier.size(44.dp))
        Spacer(Modifier.height(24.dp))
        Eyebrow(if (refresh) "COLLECTION REFRESH" else "FIRST LAUNCH", type)
        Spacer(Modifier.height(16.dp))
        Label(if (error) "Collection unavailable" else "Downloading the collection", type.Headline)
        Spacer(Modifier.height(26.dp))
        if (error) {
            Label("Check your connection and try again.", type.Caption)
            Spacer(Modifier.height(22.dp))
            NightControl("retry", Modifier.width(250.dp).height(46.dp), true, retry, openMenu) { focused ->
                Label("Retry", if (focused) type.focused(type.MenuRow) else type.MenuRow)
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Label("${((progress?.fraction ?: 0f) * 100).toInt()}", type.Percent)
                Label("%", type.Percent.copy(fontSize = 28.sp), Modifier.padding(start = 3.dp, bottom = 4.dp))
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.width(BrandSpacing.ProgressWidth).height(BrandSpacing.ProgressHeight).background(BrandColors.Track)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress?.fraction ?: 0f).background(BrandColors.Ember))
            }
            Spacer(Modifier.height(22.dp))
            Label("${progress?.fileIndex ?: 0} of ${progress?.fileCount ?: 0} tracks", type.Caption)
            Label(if (refresh) "Your saved collection stays available." else "This happens once. After that the music plays offline.", type.Caption)
        }
    }
}

/** No default Material palette, ripple, scaling or glow can enter a night control. */
@Composable internal fun NightControl(tag: String, modifier: Modifier, initial: Boolean = false,
    action: () -> Unit, longPress: () -> Unit = {}, content: @Composable BoxScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initial) requester.requestFocus() }
    Box(modifier.testTag(tag).focusRequester(requester).onFocusChanged { focused = it.isFocused }
        .background(if (focused) BrandFocus.Fill else BrandColors.Panel)
        .border(if (focused) BrandFocus.RingWidth else 0.5.dp, if (focused) BrandFocus.Ring else BrandColors.Line)
        .onKeyEvent {
            if (it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                if (it.type == KeyEventType.KeyDown) {
                    if (it.nativeKeyEvent.repeatCount == 0) { held = false; pressed = true }
                    if (it.nativeKeyEvent.isLongPress && !held) { held = true; longPress() }
                }
                if (it.type == KeyEventType.KeyUp) {
                    if (pressed && !held && !it.nativeKeyEvent.isCanceled) action()
                    pressed = false
                }
                true
            } else false
        }.semantics { onClick { action(); true } }.focusable(), contentAlignment = Alignment.Center) { content(focused) }
}

@Composable private fun BoxScope.NightMenu(s: SessionState, status: CollectionStatus?, type: BrandType,
    timer: (Int) -> Unit, tv: () -> Unit, refresh: () -> Unit, like: () -> Unit, never: () -> Unit, source: (() -> Unit)?) {
    Column(Modifier.align(Alignment.TopCenter).padding(top = 24.dp).width(BrandSpacing.PanelWidth)
        .background(BrandColors.Panel).border(0.5.dp, BrandColors.Line).padding(horizontal = 30.dp, vertical = 20.dp)) {
        Eyebrow("SLEEP TIMER", type)
        Spacer(Modifier.height(11.dp))
        Label("When it runs out, the music fades.", type.MenuSub)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(BrandSpacing.TileGap)) {
            SleepTimer.CHOICES.forEach { value ->
                NightControl("timer-$value", Modifier.weight(1f).height(BrandSpacing.TileHeight), value == 90, { timer(value) }) { focused ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Label(value.toString(), if (focused) type.focused(type.MenuFigure) else type.MenuFigure)
                        Label("min", if (focused) type.focused(type.MenuUnit) else type.MenuUnit)
                        Spacer(Modifier.height(5.dp))
                        if (s.timerMinutes == value) Dot() else Spacer(Modifier.size(6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        Label("current: ${s.timerMinutes} min", type.Hint)
        Spacer(Modifier.height(10.dp))
        fun route() = when (s.sleepRouteStatus) {
            SleepRouteStatus.NotEnabled -> "not enabled"
            SleepRouteStatus.DeviceAdmin -> "device admin"
            SleepRouteStatus.Accessibility -> "accessibility"
        }
        val rows = (if (source != null) listOf(Triple("source", "Music source", if (s.online) "Online · select for downloaded" else "Downloaded · select for online")) else emptyList()) + listOf(
            Triple("tv", "TV off", if (s.tvOff) "on · ${route()}" else "off · ${route()}"),
            Triple("refresh", if (s.online) "Online catalog" else "Refresh collection", if (s.online && s.onlinePhase == "unavailable") "unavailable · needs catalog or new launch" else "${s.playableCount} tracks"),
            Triple("like", if (s.track?.liked == true) "Unlike" else "Like", "current track"),
            Triple("never", "Never again", "skip and exclude"),
        )
        rows.forEach { (tag, title, meta) ->
            NightControl(tag, Modifier.fillMaxWidth().height(BrandSpacing.RowHeight), action = {
                when (tag) {
                    "source" -> source?.invoke()
                    "tv" -> tv()
                    "refresh" -> refresh()
                    "like" -> like()
                    "never" -> never()
                }
            }) { focused ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Label(title, if (focused) type.focused(type.MenuRow) else type.MenuRow)
                    Label(meta, type.MenuMeta)
                }
            }
        }
    }
    if (status?.phase == CollectionPhase.RefreshError) Label("Refresh failed. Your saved collection is still available.", type.Caption,
        Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
}
