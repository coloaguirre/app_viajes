package com.bitacoraviaje.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.bitacoraviaje.app.map.TrackMapState
import com.bitacoraviaje.app.ui.review.MediaReviewScreen
import com.bitacoraviaje.app.ui.review.MediaReviewUiState
import com.bitacoraviaje.app.ui.theme.BitacoraTheme
import com.bitacoraviaje.app.ui.tripdetail.MapSelection
import com.bitacoraviaje.app.ui.tripdetail.SegmentTimeline
import com.bitacoraviaje.app.ui.tripdetail.TripDetailScreen
import com.bitacoraviaje.app.ui.tripdetail.TripDetailUiState
import com.bitacoraviaje.app.ui.voicenote.VoiceNoteDetailScreen
import com.bitacoraviaje.app.ui.voicenote.VoiceNoteDetailUiState
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.Segment
import com.bitacoraviaje.core.model.SegmentStats
import com.bitacoraviaje.core.model.SegmentStatus
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNote
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TranscriptStatus
import com.bitacoraviaje.core.model.Trip
import com.bitacoraviaje.core.model.TripStatus
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.timeline.TimelineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PLAN item 2.7: trip detail (tabs synced with the map), media review and voice note detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailScreensComposeTest {
    @get:Rule
    val rule = createComposeRule()

    private val t0 = 1_760_000_000_000L
    private val trip = Trip(id = "t1", title = "Viaje de prueba", createdUtcMs = t0, startedUtcMs = t0, status = TripStatus.ACTIVE)
    private val segment = Segment(
        id = "s1", tripId = "t1", type = SegmentType.TREKKING, startedUtcMs = t0, endedUtcMs = t0 + 3_600_000, tzId = "America/Argentina/Buenos_Aires",
        samplingProfile = "WALK", recordingMode = RecordingMode.FULL, status = SegmentStatus.FINISHED, stats = SegmentStats(distanceM = 4_200.0),
    )
    private val photo = MediaItem(
        id = "m1", tripId = "t1", segmentId = "s1", kind = MediaKind.PHOTO, source = MediaSource.MEDIASTORE, contentUri = "content://media/1", displayName = "IMG_1.jpg",
        takenUtcMs = t0 + 600_000, takenTimeSource = TakenTimeSource.EXIF_WITH_OFFSET, linkMethod = LinkMethod.NONE,
    )
    private val voice = VoiceNote(id = "v1", segmentId = "s1", startedUtcMs = t0 + 1_200_000, durationMs = 7_000, lat = -34.6, lon = -58.4, audioPath = "audio/t1/v1.m4a", transcript = "hola mundo", transcriptStatus = TranscriptStatus.DONE)
    private val events = listOf(
        TimelineEvent.SegmentStart(t0, -34.6, -58.4, segment),
        TimelineEvent.Moment(photo.takenUtcMs, -34.61, -58.41, listOf(photo)),
        TimelineEvent.Voice(voice.startedUtcMs, voice.lat, voice.lon, voice),
        TimelineEvent.Highlight(t0 + 1_800_000, -34.62, -58.42, TextNote("x1", "s1", t0 + 1_800_000, -34.62, -58.42, TextNoteKind.HIGHLIGHT)),
        TimelineEvent.SegmentEnd(t0 + 3_600_000, -34.63, -58.43, segment),
    )

    @Test
    fun `trip detail lists events, switches tabs and reports taps on events and review`() {
        val tapped = mutableListOf<TimelineEvent>()
        var reviews = 0
        rule.setContent {
            var tab by remember { mutableStateOf(0) }
            BitacoraTheme {
                TripDetailScreen(
                    state = TripDetailUiState(trip = trip, segments = listOf(SegmentTimeline(segment, events, emptyList())), media = listOf(photo), reviewCount = 1, map = TrackMapState(), selectedTab = tab),
                    onBack = {},
                    onTab = { tab = it },
                    onEvent = { tapped += it },
                    onReview = { reviews++ },
                )
            }
        }
        rule.onNodeWithText("Viaje de prueba").assertIsDisplayed()
        rule.onNodeWithText("Inicio de la etapa").assertIsDisplayed()
        rule.onNodeWithText("hola mundo").performClick()
        rule.onNodeWithText("Momento destacado").performClick()
        assertEquals(listOf(events[2], events[3]), tapped)
        rule.onNodeWithTag("trip_tab_1").performClick()
        rule.onNodeWithTag("trip_map").assertIsDisplayed()
        rule.onNodeWithTag("trip_tab_2").performClick()
        rule.onNodeWithTag("photos_grid").assertIsDisplayed()
        rule.onNodeWithTag("photos_review").performClick()
        assertEquals(1, reviews)
    }

    @Test
    fun `export dialog starts with the chosen options and destination and shows the result`() {
        val started = mutableListOf<Pair<com.bitacoraviaje.app.export.ExportOptions, com.bitacoraviaje.app.export.ExportDestination>>()
        var resets = 0
        rule.setContent {
            var status by remember { mutableStateOf(com.bitacoraviaje.app.export.ExportStatus()) }
            BitacoraTheme {
                com.bitacoraviaje.app.ui.tripdetail.ExportDialog(
                    zones = 2,
                    status = status,
                    onDismiss = {},
                    onStart = { o, d -> started += o to d; status = com.bitacoraviaje.app.export.ExportStatus(finished = true, folder = "Viaje_2026-10-13", mediaWritten = 4, mediaTotal = 5, audioWritten = 2, audioTotal = 2, missing = 1, privacyPoints = 12, privacyStops = 1, privacyMedia = 3) },
                    onShareResult = {},
                    onReset = { resets++; status = com.bitacoraviaje.app.export.ExportStatus() },
                )
            }
        }
        rule.onNodeWithTag("export_opt_filtered").performClick()
        rule.onNodeWithTag("export_opt_audio").performClick()
        rule.onNodeWithTag("export_opt_privacy").performScrollTo().assertIsDisplayed() // zones exist → offered, on by default (PLAN 5.5)
        rule.onNodeWithTag("export_opt_strip_exif").performScrollTo().performClick()
        rule.onNodeWithTag("export_downloads").performClick()
        assertEquals(listOf(com.bitacoraviaje.app.export.ExportOptions(includeMedia = true, includeAudio = false, includeFilteredPoints = true, applyPrivacyZones = true, stripExifGps = false) to com.bitacoraviaje.app.export.ExportDestination.DOWNLOADS), started)
        rule.onNodeWithTag("export_done").assertIsDisplayed()
        rule.onNodeWithText("Fotos y videos: 4 de 5 · Audios: 2 de 2").assertIsDisplayed()
        rule.onNodeWithText("Zonas de privacidad: 12 puntos omitidos, 1 parada omitida, 3 fotos y videos sin ubicación").assertIsDisplayed()
        rule.onNodeWithTag("export_share_result").assertIsDisplayed()
        // A finished job offers a new export (E2E 5.5: the dialog used to be stuck on the last result).
        rule.onNodeWithTag("export_reset").performClick()
        assertEquals(1, resets)
        rule.onNodeWithTag("export_downloads").assertIsDisplayed()
    }

    @Test
    fun `offline dialog offers both detail levels with an estimate, shows progress and the result`() {
        val chosen = mutableListOf<Int>()
        val boxes = listOf(com.bitacoraviaje.core.model.BoundingBox(-34.61, -58.42, -34.59, -58.38))
        rule.setContent {
            var st by remember { mutableStateOf(com.bitacoraviaje.app.ui.tripdetail.OfflineUiState(available = true, stageBoxes = boxes)) }
            BitacoraTheme {
                com.bitacoraviaje.app.ui.tripdetail.OfflineDialog(
                    state = st,
                    // As in production: a start publishes progress; "Cancelar" drops the run and the dialog returns to the chooser (review I5).
                    onStart = { z -> chosen += z; st = st.copy(running = true, progress = com.bitacoraviaje.app.map.OfflineProgress("t", 40, 1_048_576, fraction = 0.4f, region = 1, regions = 2)) },
                    onCancel = { st = st.copy(running = false, progress = null) },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithTag("offline_zoom_12").assertIsDisplayed()
        rule.onNodeWithTag("offline_zoom_14").assertIsDisplayed()
        rule.onNodeWithTag("offline_zoom_12").performClick()
        assertEquals(listOf(12), chosen)
        rule.onNodeWithText("Zona 1 de 2 · 40 teselas · 1 MB").assertIsDisplayed()
        rule.onNodeWithTag("offline_cancel").performClick()
        rule.onNodeWithTag("offline_zoom_12").assertIsDisplayed()
    }

    @Test
    fun `offline dialog shows the finished result, the error and the busy state`() {
        val boxes = listOf(com.bitacoraviaje.core.model.BoundingBox(-34.61, -58.42, -34.59, -58.38))
        var st by mutableStateOf(com.bitacoraviaje.app.ui.tripdetail.OfflineUiState(available = true, stageBoxes = boxes, progress = com.bitacoraviaje.app.map.OfflineProgress("t", 166, 44_040_192, 1f, done = true)))
        rule.setContent { BitacoraTheme { com.bitacoraviaje.app.ui.tripdetail.OfflineDialog(state = st, onStart = {}, onCancel = {}, onDismiss = {}) } }
        rule.onNodeWithTag("offline_done").assertIsDisplayed()
        rule.onNodeWithText("Listo: 166 teselas, 42 MB. El mapa de esta zona se ve sin red.").assertIsDisplayed()
        st = st.copy(progress = com.bitacoraviaje.app.map.OfflineProgress("t", done = true, error = "tile_limit:6000"))
        rule.onNodeWithTag("offline_error").assertIsDisplayed()
        st = st.copy(progress = null, busyElsewhere = true)
        rule.onNodeWithTag("offline_busy").assertIsDisplayed()
    }

    @Test
    fun `media review exposes the three actions and the camera fix only with a suggestion`() {
        var useTrack = 0
        var excluded = 0
        var fixed = 0
        rule.setContent {
            var state by remember { mutableStateOf(MediaReviewUiState(queue = listOf(photo), selected = photo)) }
            BitacoraTheme {
                MediaReviewScreen(
                    state = state,
                    onBack = {},
                    onSelect = {},
                    onUseTrackTime = { useTrack++ },
                    onExclude = { excluded++; state = state.copy(suggestedOffsetMs = 3_720_000, suggestedCamera = "Canon EOS") },
                    onApplyOffset = { fixed++ },
                )
            }
        }
        rule.onNodeWithTag("review_use_track").performClick()
        rule.onNodeWithTag("review_exclude").performClick()
        rule.onNodeWithTag("review_fix_camera").assertIsDisplayed().performClick()
        rule.onNodeWithText("Corregir el reloj de Canon EOS (+1 h 02 min) en todas sus fotos").assertIsDisplayed()
        assertEquals(listOf(1, 1, 1), listOf(useTrack, excluded, fixed))
    }

    @Test
    fun `media review shows the empty state when the queue is empty but keeps the camera fix offer`() {
        rule.setContent { BitacoraTheme { MediaReviewScreen(MediaReviewUiState(suggestedOffsetMs = -960_000, suggestedCamera = "CamA"), {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("review_empty").assertIsDisplayed()
        rule.onNodeWithTag("review_fix_camera").assertIsDisplayed()
    }

    @Test
    fun `voice note detail enables save only when the text changed and offers retry on failure`() {
        var saved: String? = null
        var retried = 0
        rule.setContent {
            BitacoraTheme {
                VoiceNoteDetailScreen(
                    state = VoiceNoteDetailUiState(note = voice.copy(transcriptStatus = TranscriptStatus.FAILED)),
                    onBack = {},
                    onTogglePlay = {},
                    onSave = { saved = it },
                    onRetry = { retried++ },
                )
            }
        }
        rule.onNodeWithTag("voice_save").assertIsNotEnabled()
        rule.onNodeWithTag("voice_transcript").performTextReplacement("hola mundo corregido")
        rule.onNodeWithTag("voice_save").assertIsEnabled().performClick()
        rule.onNodeWithTag("voice_retry").performClick()
        assertEquals("hola mundo corregido", saved)
        assertEquals(1, retried)
    }

    @Test
    fun `places tab shows cards with source and license and reports approve, edit, discard and refresh`() {
        val actions = mutableListOf<com.bitacoraviaje.app.ui.tripdetail.PlaceAction>()
        val place = com.bitacoraviaje.core.model.Place("wd-Q1", "El Chaltén", -49.33, -72.88, kind = "municipality", admin1 = "Santa Cruz", source = com.bitacoraviaje.core.model.PlaceSource.WIKIPEDIA)
        val wiki = com.bitacoraviaje.core.model.Enrichment("wd-Q1-wikipedia-es", "wd-Q1", "es", "El Chaltén", "El Chaltén es un municipio.", "Wikipedia", "https://es.wikipedia.org/wiki/El_Chalt%C3%A9n", "CC BY-SA 4.0", fetchedUtcMs = t0)
        val facts = wiki.copy(id = "wd-Q1-wikidata", summary = "Altitud: 397 m", sourceName = "Wikidata", sourceUrl = "https://www.wikidata.org/wiki/Q1", license = "CC0", userApproved = true)
        val card = com.bitacoraviaje.app.ui.tripdetail.PlaceCard(segment, place, com.bitacoraviaje.core.model.SegmentPlace("s1", "wd-Q1", t0, 40 * 60_000L, 3.0), listOf(wiki, facts))
        val bare = com.bitacoraviaje.app.ui.tripdetail.PlaceCard(segment, place.copy(id = "osm-N5", name = "Cerro Paredón", kind = "peak", source = com.bitacoraviaje.core.model.PlaceSource.OVERPASS), com.bitacoraviaje.core.model.SegmentPlace("s1", "osm-N5", t0, null, 0.5), emptyList())
        rule.setContent {
            BitacoraTheme {
                TripDetailScreen(
                    state = TripDetailUiState(trip = trip, segments = listOf(SegmentTimeline(segment, events, emptyList())), selectedTab = 3, places = listOf(card, bare)),
                    onBack = {}, onTab = {}, onEvent = {}, onReview = {}, onPlaceAction = { actions += it },
                )
            }
        }
        rule.onNodeWithText("El Chaltén").assertIsDisplayed()
        rule.onNodeWithText("Wikipedia · CC BY-SA 4.0").assertIsDisplayed()
        rule.onNodeWithText("Wikidata · CC0").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Se usa en el video").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("place_approve_wd-Q1-wikipedia-es").performScrollTo().performClick()
        rule.onNodeWithTag("place_edit_wd-Q1-wikipedia-es").performScrollTo().performClick()
        rule.onNodeWithTag("place_edit_text").performTextReplacement("Texto corregido")
        rule.onNodeWithTag("place_edit_save").performClick()
        rule.onNodeWithTag("places_list").performScrollToNode(hasTestTag("place_osm-N5"))
        rule.onNodeWithText("Sin texto en internet; solo el nombre del mapa.").assertIsDisplayed()
        rule.onNodeWithTag("place_discard_osm-N5").performScrollTo().performClick()
        rule.onNodeWithTag("places_refresh").performClick()
        rule.onNodeWithTag("places_list").performScrollToNode(hasTestTag("places_refresh_s1"))
        rule.onNodeWithTag("places_refresh_s1").performClick()
        assertEquals(
            listOf(
                com.bitacoraviaje.app.ui.tripdetail.PlaceAction.Approve("wd-Q1-wikipedia-es", true),
                com.bitacoraviaje.app.ui.tripdetail.PlaceAction.Edit("wd-Q1-wikipedia-es", "Texto corregido"),
                com.bitacoraviaje.app.ui.tripdetail.PlaceAction.Discard("s1", "osm-N5"),
                com.bitacoraviaje.app.ui.tripdetail.PlaceAction.Refresh(),
                com.bitacoraviaje.app.ui.tripdetail.PlaceAction.Refresh("s1"),
            ),
            actions,
        )
    }

    @Test
    fun `places tab empty state and disabled refresh while a search runs`() {
        rule.setContent {
            BitacoraTheme {
                TripDetailScreen(state = TripDetailUiState(trip = trip, selectedTab = 3, placesWork = com.bitacoraviaje.app.ui.tripdetail.PlacesWork.RUNNING), onBack = {}, onTab = {}, onEvent = {}, onReview = {})
            }
        }
        rule.onNodeWithTag("places_empty").assertIsDisplayed()
        rule.onNodeWithText("Buscando lugares…").assertIsDisplayed()
        rule.onNodeWithTag("places_refresh").assertIsNotEnabled()
    }

    @Test
    fun `rewrite action appears only with an llm key and restore only for an edited text`() {
        val actions = mutableListOf<com.bitacoraviaje.app.ui.tripdetail.PlaceAction>()
        val place = com.bitacoraviaje.core.model.Place("wd-Q1", "El Chaltén", -49.33, -72.88, source = com.bitacoraviaje.core.model.PlaceSource.WIKIPEDIA)
        val wiki = com.bitacoraviaje.core.model.Enrichment("wd-Q1-wikipedia-es", "wd-Q1", "es", "El Chaltén", "Texto.", "Wikipedia", "https://es.wikipedia.org/wiki/X", "CC BY-SA 4.0", fetchedUtcMs = t0)
        fun state(key: Boolean, e: com.bitacoraviaje.core.model.Enrichment) = TripDetailUiState(trip = trip, selectedTab = 3, llmAvailable = key, places = listOf(com.bitacoraviaje.app.ui.tripdetail.PlaceCard(segment, place, com.bitacoraviaje.core.model.SegmentPlace("s1", "wd-Q1", t0), listOf(e))))
        var ui by mutableStateOf(state(key = false, e = wiki))
        rule.setContent { BitacoraTheme { TripDetailScreen(state = ui, onBack = {}, onTab = {}, onEvent = {}, onReview = {}, onPlaceAction = { actions += it }) } }
        rule.onAllNodesWithTag("place_rewrite_wd-Q1-wikipedia-es").assertCountEquals(0)
        rule.onAllNodesWithTag("place_restore_wd-Q1-wikipedia-es").assertCountEquals(0)
        ui = state(key = true, e = wiki.copy(summary = "Reescrito.", originalSummary = "Texto.", llmRewritten = true))
        rule.onNodeWithText("Reescrito.").assertIsDisplayed()
        rule.onNodeWithText("Wikipedia · CC BY-SA 4.0 · Texto reescrito").assertIsDisplayed()
        rule.onNodeWithTag("place_rewrite_wd-Q1-wikipedia-es").performScrollTo().performClick()
        rule.onNodeWithTag("place_restore_wd-Q1-wikipedia-es").performScrollTo().performClick()
        assertEquals(listOf(com.bitacoraviaje.app.ui.tripdetail.PlaceAction.Rewrite("wd-Q1-wikipedia-es"), com.bitacoraviaje.app.ui.tripdetail.PlaceAction.Restore("wd-Q1-wikipedia-es")), actions)
    }

    // Backlog D2: the map card for a tapped marker or track point.

    @Test
    fun `map tab shows the selection card with time, progress, speed and altitude and reports close`() {
        var closed = 0
        val sel = MapSelection(segment, photo.takenUtcMs, distanceM = 1_250.0, speedMps = 1.25, altM = 812.6, event = events[1])
        rule.setContent {
            BitacoraTheme {
                TripDetailScreen(
                    state = TripDetailUiState(trip = trip, segments = listOf(SegmentTimeline(segment, events, emptyList())), map = TrackMapState(), selectedTab = 1, mapSelection = sel),
                    onBack = {}, onTab = {}, onEvent = {}, onReview = {},
                    onCloseSelection = { closed++ },
                )
            }
        }
        rule.onNodeWithTag("map_selection").assertIsDisplayed()
        rule.onNodeWithText("Foto").assertIsDisplayed()
        // t0 + 10 min in Buenos Aires: 2025-10-09 06:03 local for t0 = 1_760_000_000_000.
        rule.onNodeWithTag("map_selection_facts").assertTextEquals("06:03 · 1,3 km de la etapa · 4,5 km/h · 813 m de altitud")
        rule.onNodeWithTag("map_selection_close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `map card for a bare track point has no event title and omits unknown facts`() {
        val sel = MapSelection(segment, t0, distanceM = 0.0, speedMps = null, altM = null, event = null)
        rule.setContent {
            BitacoraTheme {
                TripDetailScreen(
                    state = TripDetailUiState(trip = trip, segments = emptyList(), map = TrackMapState(), selectedTab = 1, mapSelection = sel),
                    onBack = {}, onTab = {}, onEvent = {}, onReview = {},
                )
            }
        }
        rule.onNodeWithText("Punto del recorrido").assertIsDisplayed()
        rule.onNodeWithTag("map_selection_facts").assertTextEquals("05:53 · 0 m de la etapa")
    }

    @Test
    fun `map card names the stage when the trip has several and the map slot forwards taps`() {
        val taps = mutableListOf<com.bitacoraviaje.app.map.MapTap>()
        val other = segment.copy(id = "s2", title = "Día 2")
        val sel = MapSelection(other, t0, distanceM = 2_000.0, speedMps = null, altM = null, event = null)
        rule.setContent {
            BitacoraTheme {
                TripDetailScreen(
                    state = TripDetailUiState(trip = trip, segments = listOf(SegmentTimeline(segment, events, emptyList()), SegmentTimeline(other, emptyList(), emptyList())), map = TrackMapState(), selectedTab = 1, mapSelection = sel),
                    onBack = {}, onTab = {}, onEvent = {}, onReview = {},
                    map = { _, m, tap -> androidx.compose.material3.Button(onClick = { tap(com.bitacoraviaje.app.map.MapTap.Marker("x")) }, modifier = m) { androidx.compose.material3.Text("mapa") } },
                    onMapTap = { taps += it },
                )
            }
        }
        rule.onNodeWithTag("map_selection_facts").assertTextEquals("jue 9 oct. 05:53 · Día 2 · 05:53 · 2,0 km de la etapa")
        rule.onNodeWithTag("trip_map").performClick()
        assertEquals(listOf<com.bitacoraviaje.app.map.MapTap>(com.bitacoraviaje.app.map.MapTap.Marker("x")), taps)
    }

    // Backlog D3: weather in the timeline rows and in the map card.

    @Test
    fun `timeline shows the weather block row and tags a photo with its hour, and the map card shows it too`() {
        val sample = com.bitacoraviaje.core.model.WeatherSample("w1", "s1", photo.takenUtcMs, -34.6, -58.4, 22.6, 21.0, 0.0, 12.0, 20, 1)
        val block = com.bitacoraviaje.core.timeline.WeatherDigest.blocks(listOf(sample), segment.tzId).single()
        val withWeather = listOf(
            events[0],
            TimelineEvent.Weather(sample.timeUtcMs - 1, -34.6, -58.4, block),
            TimelineEvent.Moment(photo.takenUtcMs, -34.61, -58.41, listOf(photo), weather = sample),
            events[4],
        )
        rule.setContent {
            var tab by remember { mutableStateOf(0) }
            BitacoraTheme {
                TripDetailScreen(
                    state = TripDetailUiState(
                        trip = trip, segments = listOf(SegmentTimeline(segment, withWeather, emptyList())), map = TrackMapState(), selectedTab = tab,
                        mapSelection = MapSelection(segment, photo.takenUtcMs, 100.0, null, null, withWeather[2], weather = com.bitacoraviaje.core.timeline.WeatherDigest.brief(sample)),
                    ),
                    onBack = {}, onTab = { tab = it }, onEvent = {}, onReview = {},
                )
            }
        }
        rule.onNodeWithText("Mañana: Mayormente despejado, 23 °C").assertIsDisplayed()
        rule.onNodeWithText("Foto · ☀️ 23 °C").assertIsDisplayed()
        rule.onNodeWithTag("trip_tab_1").performClick()
        rule.onNodeWithTag("map_selection_facts").assertTextEquals("06:03 · 100 m de la etapa · ☀️ 23 °C")
    }
}
