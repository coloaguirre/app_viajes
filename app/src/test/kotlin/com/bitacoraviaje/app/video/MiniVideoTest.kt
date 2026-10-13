@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // Media3 Transformer API (PLAN 4.6)

package com.bitacoraviaje.app.video

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bitacoraviaje.core.model.Enrichment
import com.bitacoraviaje.core.model.LinkMethod
import com.bitacoraviaje.core.model.MediaItem
import com.bitacoraviaje.core.model.MediaKind
import com.bitacoraviaje.core.model.MediaSource
import com.bitacoraviaje.core.model.Place
import com.bitacoraviaje.core.model.PlaceSource
import com.bitacoraviaje.core.model.RecordingMode
import com.bitacoraviaje.core.model.SegmentPlace
import com.bitacoraviaje.core.model.SegmentType
import com.bitacoraviaje.core.model.TakenTimeSource
import com.bitacoraviaje.core.model.TextNoteKind
import com.bitacoraviaje.core.model.TrackPoint
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.model.VoiceNote
import com.bitacoraviaje.core.videoplan.VideoPlan
import com.bitacoraviaje.data.db.BitacoraDatabase
import com.bitacoraviaje.data.repository.Clock
import com.bitacoraviaje.data.repository.MediaRepository
import com.bitacoraviaje.data.repository.NotesRepository
import com.bitacoraviaje.data.repository.PlacesRepository
import com.bitacoraviaje.data.repository.TrackingRepository
import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN 4.6: the stage plan from Room, the cards and the Media3 composition (the export itself needs a device: emulator E2E). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real Skia: glyphs are drawn, so text-only cards are testable
class MiniVideoTest {
    private lateinit var db: BitacoraDatabase
    private lateinit var tracking: TrackingRepository
    private lateinit var planner: MiniVideoPlanner
    private val t0 = 1_760_000_000_000L
    private var now = t0

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BitacoraDatabase::class.java).allowMainThreadQueries().build()
        tracking = TrackingRepository(db, Clock { now })
        planner = MiniVideoPlanner(tracking, MediaRepository(db), NotesRepository(db), PlacesRepository(db))
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun stage(): String {
        val s = tracking.startSession(SegmentType.TREKKING, Transport.WALK, RecordingMode.FULL, "America/Argentina/Buenos_Aires", title = "Laguna de los Tres", tripTitle = "Patagonia")
        tracking.insertPoints((0 until 120).map { i -> TrackPoint(segmentId = s.id, timeUtcMs = t0 + i * 30_000L, lat = -49.33 + i * 1e-4, lon = -72.9 + i * 5e-5, altM = 400.0 + i) })
        MediaRepository(db).upsertAll(
            (0 until 10).map { i ->
                MediaItem("m$i", s.tripId, s.id, MediaKind.PHOTO, MediaSource.MEDIASTORE, "content://media/$i", i.toLong(), "IMG_$i.jpg", "image/jpeg", 1, 4000, 3000, null, 0, t0 + i * 330_000L, TakenTimeSource.EXIF_WITH_OFFSET, null, null, null, null, -49.33, -72.9, LinkMethod.TRACK_NEAREST, 0.7, 0, false, null, null, i == 3)
            },
        )
        NotesRepository(db).addVoiceNote(VoiceNote("v1", s.id, t0 + 1_000_000, 5_000, -49.33, -72.9, null, "audio/t/v1.m4a", transcript = "Qué viento", transcriptStatus = com.bitacoraviaje.core.model.TranscriptStatus.DONE))
        NotesRepository(db).addTextNote(s.id, TextNoteKind.HIGHLIGHT, t0 + 2_000_000, -49.32, -72.9)
        val places = PlacesRepository(db)
        places.replaceSegmentPlaces(
            s.id,
            listOf(Place("wd-Q1", "El Chaltén", -49.33, -72.88, kind = "municipality", source = PlaceSource.WIKIPEDIA)),
            listOf(
                Enrichment("wd-Q1-wikipedia-es", "wd-Q1", "es", "El Chaltén", "Un pueblo de montaña.", "Wikipedia", "https://es.wikipedia.org/wiki/El_Chalt%C3%A9n", "CC BY-SA 4.0", fetchedUtcMs = t0, userApproved = true),
                Enrichment("wd-Q1-wikidata", "wd-Q1", "es", "El Chaltén", "Altitud: 397 m", "Wikidata", "https://www.wikidata.org/wiki/Q1", "CC0", fetchedUtcMs = t0),
            ),
            listOf(SegmentPlace(s.id, "wd-Q1", t0, 600_000, 3.0)),
        )
        now = t0 + 3_600_000
        tracking.finishSegment(s.id)
        return s.id
    }

    @Test
    fun `the stage plan is the one minute preset with approved places only`() = runTest {
        val id = stage()
        val p = assertNotNull(planner.prepare(id))
        assertTrue(abs(p.plan.total_duration_ms - 60_000) <= 3_000, "total ${p.plan.total_duration_ms}")
        val kinds = p.plan.scenes.map { it::class.simpleName }
        assertEquals("Title", kinds.first()); assertEquals("Credits", kinds.last())
        assertTrue("MapRoute" in kinds && "PlaceCard" in kinds && "Photo" in kinds && "Stats" in kinds, kinds.toString())
        val cards = p.plan.scenes.filterIsInstance<VideoPlan.PlaceCard>()
        assertEquals(listOf("Wikipedia (CC BY-SA 4.0)"), cards.map { it.source }, "only the approved text")
        assertTrue(p.plan.scenes.filterIsInstance<VideoPlan.Photo>().any { it.subtitle_from_voice == "v1" })
        assertTrue(p.plan.scenes.filterIsInstance<VideoPlan.Photo>().any { it.media == "m3" }, "the favourite is in")
        val credits = p.plan.scenes.last() as VideoPlan.Credits
        assertTrue(credits.lines.any { it.contains("Wikipedia — El Chaltén") } && credits.lines.any { it.contains("MapTiler") })
    }

    @Test
    fun `cards draw something on every pixel class and the route card has a path`() {
        fun nonBlank(b: Bitmap, min: Int = 8): Boolean { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return px.toSet().size > min }
        val w = 384; val h = 216
        assertTrue(nonBlank(Cards.title(w, h, "Patagonia 2026", "2026-03-14", null)))
        assertTrue(nonBlank(Cards.chapter(w, h, "Trekking a Laguna de los Tres", "2026-03-14")))
        assertTrue(nonBlank(Cards.place(w, h, "El Chaltén", "Un pueblo. ".repeat(40), "Wikipedia (CC BY-SA 4.0)")))
        assertTrue(nonBlank(Cards.stats(w, h, "Etapa", listOf("distancia" to Cards.fmtKm(8300.0), "en movimiento" to Cards.fmtDur(8_040_000), "desnivel +" to Cards.fmtM(1204.0)))))
        assertTrue(nonBlank(Cards.credits(w, h, listOf("Datos del mapa © colaboradores de OpenStreetMap (ODbL)", "Texto: Wikipedia — X (CC BY-SA 4.0)"))))
        assertTrue(nonBlank(Cards.caption(w, h, "Qué viento"), min = 2), "panel over a transparent frame")
        val pts = (0 until 50).map { i -> TrackPoint(segmentId = "s", timeUtcMs = i.toLong(), lat = -49.33 + i * 1e-3, lon = -72.9 + i * 1e-3) }
        assertTrue(nonBlank(Cards.route(w, h, pts, null, "© OpenStreetMap contributors")))
        assertEquals("8,3 km", Cards.fmtKm(8300.0)); assertEquals("2 h 14 min", Cards.fmtDur(8_040_000)); assertEquals("1.204 m", Cards.fmtM(1204.4))
    }

    @Test
    fun `the composition holds one item per renderable scene and skips photos without a uri`() = runTest {
        val id = stage()
        val p = assertNotNull(planner.prepare(id))
        val work = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "render-test").apply { mkdirs() }
        val media = MediaRepository(db).allOfTrip(tracking.getSegment(id)!!.tripId).associateBy { it.id }.mapValues { (_, m) -> if (m.id == "m0") m.copy(contentUri = null) else m }
        val built = MiniVideoComposer(ApplicationProvider.getApplicationContext(), work, 384, 216).build(p.plan, p.input, media, emptyMap(), tracking.validPoints(id), null)
        val photos = p.plan.scenes.count { it is VideoPlan.Photo }
        val expectedSkipped = if (p.plan.scenes.any { it is VideoPlan.Photo && it.media == "m0" }) 1 else 0
        assertEquals(expectedSkipped, built.skipped.size)
        assertEquals(p.plan.scenes.size - expectedSkipped, built.composition.sequences.first().editedMediaItems.size)
        assertTrue(built.durationMs > 50_000 && photos > 0)
        assertTrue(work.listFiles()!!.any { it.name.startsWith("title_") && it.length() > 1000 }, "cards are written as JPEG files")
    }
}
