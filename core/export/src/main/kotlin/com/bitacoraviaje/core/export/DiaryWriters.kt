package com.bitacoraviaje.core.export

import com.bitacoraviaje.core.model.PauseKind
import com.bitacoraviaje.core.model.Transport
import com.bitacoraviaje.core.timeline.TimelineEvent
import com.bitacoraviaje.core.timeline.TimelineInput
import com.bitacoraviaje.core.timeline.buildTimeline
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale

/** Shared rendering of the chronological diary used by Markdown and HTML (DISENO §9, §12.3). */
internal object Diary {
    data class Day(val date: String, val segments: List<Pair<SegmentExport, List<TimelineEvent>>>)

    fun days(trip: TripExport): List<Day> {
        val bySeg = trip.segments.map { se ->
            se to buildTimeline(TimelineInput(se.segment, se.points.filter { !it.isFilteredOut }, se.pauses, se.stops, se.media, se.voiceNotes, se.textNotes, nowUtcMs = trip.exportedUtcMs))
        }
        return bySeg.groupBy { (se, _) -> Iso.localDate(se.segment.startedUtcMs, se.segment.tzId) }.toSortedMap().map { (d, l) -> Day(d, l) }
    }

    fun km(m: Double): String = if (m < 1000) "${m.toInt()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)

    fun dur(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        return if (h > 0) "$h h ${String.format(Locale.ROOT, "%02d", m)} min" else "$m min"
    }

    fun transport(t: Transport?): String = when (t) {
        Transport.WALK, null -> "a pie"; Transport.BIKE -> "en bici"; Transport.CAR -> "en auto"; Transport.BUS -> "en micro"
        Transport.TRAIN -> "en tren"; Transport.BOAT -> "en barco"; Transport.PLANE -> "en avión"; Transport.MIXED -> "medio mixto"
    }

    fun segmentTitle(se: SegmentExport): String {
        val s = se.segment
        val kind = when (s.type.name) { "TREKKING" -> "Trekking"; "EXCURSION" -> "Excursión"; else -> "Etapa" }
        return listOfNotNull(s.displayName ?: kind, transport(s.transport), s.stats.distanceM?.let { km(it) }, s.stats.movingTimeMs?.let { dur(it) }).joinToString(" · ")
    }

    /** One line of plain text per event, without media links (callers add those). */
    fun line(e: TimelineEvent, trip: TripExport): String = when (e) {
        is TimelineEvent.SegmentStart -> "Inicio"
        is TimelineEvent.SegmentEnd -> "Fin"
        is TimelineEvent.Pause -> when (e.kind) { PauseKind.AUTO_REST -> "Reposo automático"; PauseKind.NESTED -> "Pausa por excursión"; PauseKind.USER -> "Pausa" }
        is TimelineEvent.Resume -> "Reanudado tras ${dur(e.pausedMs)}"
        is TimelineEvent.StopArrived -> e.stop.userLabel?.let { "Llegada a $it" } ?: "Llegada a una parada"
        is TimelineEvent.StopLeft -> "Salida tras ${dur(e.dwellMs)}"
        is TimelineEvent.Moment -> if (e.isSingle) (e.media.first().userCaption ?: if (e.hasVideo) "Video" else "Foto") else "${e.media.size} fotos y videos"
        is TimelineEvent.Voice -> e.note.displayText ?: "Nota de voz (sin transcripción)"
        is TimelineEvent.Text -> e.note.text ?: "Lugar marcado"
        is TimelineEvent.Highlight -> "Momento destacado"
        is TimelineEvent.TransportChange -> "Cambio de medio: ${transport(e.transport)}"
        is TimelineEvent.DayChange -> "Nuevo día: ${e.localDate}"
        is TimelineEvent.Summary -> "Recorriste ${km(e.distanceM)} en ${dur(e.durationMs)}"
    }.also { trip.hashCode() }
}

class MarkdownWriter(override val path: String = "bitacora.md") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        w.write("# ${trip.trip.title}\n\n")
        trip.trip.description?.let { w.write("$it\n\n") }
        for (day in Diary.days(trip)) {
            w.write("## ${day.date}\n\n")
            for ((se, events) in day.segments) {
                val z = se.segment.tzId
                w.write("### ${Diary.segmentTitle(se)}\n\n")
                for (e in events) {
                    val t = Iso.localTime(e.timeUtcMs, z)
                    w.write("- **$t** ${Diary.line(e, trip)}")
                    if (e is TimelineEvent.Moment) e.media.forEach { m -> trip.mediaFiles[m.id]?.let { w.write("\n  ![${m.displayName ?: ""}]($it)") } }
                    if (e is TimelineEvent.Voice) trip.audioFiles[e.note.id]?.let { w.write(" ([audio]($it))") }
                    w.write("\n")
                }
                w.write("\n")
            }
        }
        w.write("---\n\n")
        trip.attributions.forEach { w.write("- ${it.name} — ${it.license} — ${it.url}\n") }
        w.write("\nExportado por Bitácora de Viaje ${trip.appVersion} el ${Iso.local(trip.exportedUtcMs, trip.tzId)}.\n")
        w.flush()
    }
}

/**
 * Self-contained diary: Leaflet from a CDN, the data embedded as JSON, map + timeline +
 * photos with relative paths; opens with a double click from the ZIP folder.
 */
class HtmlWriter(override val path: String = "bitacora.html") : TripWriter {
    override fun write(trip: TripExport, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        val data = TripJson.json.encodeToString(TripJson.Doc.serializer(), TripJson.build(trip)).replace("</", "<\\/")
        val title = htmlEsc(trip.trip.title)
        w.write(
            """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title · Bitácora de Viaje</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<style>
body{margin:0;font-family:system-ui,sans-serif;display:grid;grid-template-columns:1fr 420px;height:100vh}
#map{height:100vh}
#side{overflow:auto;padding:16px;border-left:1px solid #ddd}
h1{font-size:1.3rem;margin:0 0 8px}h2{font-size:1.05rem;margin:18px 0 6px;color:#1f3864}h3{font-size:.95rem;margin:12px 0 4px}
.ev{padding:4px 0;border-bottom:1px solid #eee;cursor:pointer}.ev time{color:#666;font-variant-numeric:tabular-nums;margin-right:6px}
.ev img{display:block;max-width:100%;margin-top:4px;border-radius:4px}
footer{font-size:.75rem;color:#666;margin-top:24px}
@media (max-width:800px){body{grid-template-columns:1fr;grid-template-rows:50vh 1fr}#map{height:50vh}}
</style>
</head>
<body>
<div id="map"></div>
<div id="side"><h1>$title</h1><div id="timeline"></div><footer id="credits"></footer></div>
<script id="trip-data" type="application/json">$data</script>
<script>
(function(){
  const trip = JSON.parse(document.getElementById('trip-data').textContent);
  const map = L.map('map');
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'}).addTo(map);
  const colors=['#1f3864','#648f1f','#d43f1f','#a41f8f','#ff7f00','#c04040'];
  const bounds=[];const side=document.getElementById('timeline');
  const fmt=t=>t.slice(11,16);
  trip.segments.forEach((s,i)=>{
    const line=s.points.filter(p=>!p.filtered).map(p=>[p.lat,p.lon]);
    if(line.length>1){L.polyline(line,{color:colors[i%colors.length],weight:4}).addTo(map);line.forEach(p=>bounds.push(p));}
    const h2=document.createElement('h2');h2.textContent=(s.started||'').slice(0,10)+' · '+(s.title||s.type.toLowerCase())+(s.stats.distance_m?' · '+(s.stats.distance_m/1000).toFixed(1)+' km':'');side.appendChild(h2);
    const events=[];
    s.media.forEach(m=>events.push({t:m.taken,lat:m.lat,lon:m.lon,label:m.caption||(m.kind==='VIDEO'?'Video':'Foto'),file:m.file,kind:'photo'}));
    s.voice_notes.forEach(v=>events.push({t:v.started,lat:v.lat,lon:v.lon,label:v.transcript||'Nota de voz',kind:'voice',file:v.file}));
    s.text_notes.forEach(n=>events.push({t:n.time,lat:n.lat,lon:n.lon,label:n.text||(n.kind==='HIGHLIGHT'?'Momento destacado':n.kind==='TRANSPORT_CHANGE'?'Cambio de medio: '+(n.text||''):'Lugar marcado'),kind:'note'}));
    s.stops.forEach(st=>events.push({t:st.start,lat:st.lat,lon:st.lon,label:(st.user_label||'Parada')+' · '+Math.round((st.end_utc_ms-st.start_utc_ms)/60000)+' min',kind:'stop'}));
    events.sort((a,b)=>a.t<b.t?-1:1);
    events.forEach(e=>{
      const d=document.createElement('div');d.className='ev';
      d.innerHTML='<time>'+fmt(e.t)+'</time>'+e.label.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
      if(e.kind==='photo'&&e.file&&!/\.(mp4|mov|3gp|webm)$/i.test(e.file)){const img=document.createElement('img');img.loading='lazy';img.src=e.file;d.appendChild(img);}
      if(e.kind==='voice'&&e.file){const a=document.createElement('audio');a.controls=true;a.src=e.file;a.style.width='100%';d.appendChild(a);}
      side.appendChild(d);
      if(e.lat!=null&&e.lon!=null){const mk=L.circleMarker([e.lat,e.lon],{radius:6,color:e.kind==='photo'?'#1f8f64':e.kind==='voice'?'#8f1fa4':'#ff7f00',fillOpacity:.9}).addTo(map).bindPopup(e.label);d.onclick=()=>{map.setView([e.lat,e.lon],16);mk.openPopup();};bounds.push([e.lat,e.lon]);}
    });
  });
  if(bounds.length)map.fitBounds(bounds,{padding:[20,20]});else map.setView([0,0],2);
  document.getElementById('credits').innerHTML=trip.attributions.map(a=>a.name+' — '+a.license).join(' · ')+'<br>Exportado por Bitácora de Viaje '+trip.app_version+' · '+trip.exported_utc;
})();
</script>
</body>
</html>
""",
        )
        w.flush()
    }
}
