package com.alto.audio

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

val Ink = Color(0xFF05070D)
val Navy = Color(0xFF0B1530)
val Blue = Color(0xFF4DA3FF)
val Hot = Color(0xFFFF5A4A)

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF2F7BFF), background = Ink, surface = Navy, onBackground = Color(0xFFE8EEFC), onSurface = Color(0xFFE8EEFC))) {
                Surface(color = Ink, modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

fun fmt(frames: Long, sr: Int): String { val s = frames / sr; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun Logo() {
    Canvas(Modifier.size(46.dp)) {
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF17387A), Color(0xFF02040A))), size = size, cornerRadius = CornerRadius(size.width * 0.25f))
        val hs = listOf(0.25f, 0.38f, 0.52f, 0.68f)
        hs.forEachIndexed { i, h ->
            val w = size.width * 0.125f
            val x = size.width * (0.19f + i * 0.16f)
            val ht = size.height * h
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9AD7FF), Color(0xFF2F7BFF)), startY = size.height * 0.8f - ht, endY = size.height * 0.8f),
                topLeft = Offset(x, size.height * 0.8f - ht), size = Size(w, ht), cornerRadius = CornerRadius(w / 2))
        }
    }
}

@Composable
fun Wave(c: Clip, gain: Float, sel: Pair<Float, Float>?, head: Float, onTap: (Float) -> Unit, onSel: (Float, Float) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF030713))
            .pointerInput(c) { detectTapGestures { o -> onTap((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(c) {
                var a = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o -> a = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { ch, _ -> val b = (ch.position.x / size.width).coerceIn(0f, 1f); onSel(min(a, b), max(a, b)) }
                )
            }
    ) {
        val w = size.width; val h = size.height; val n = c.peaks.size; val wi = max(1, w.toInt())
        for (x in 0 until wi) {
            val i0 = (x.toLong() * n / wi).toInt().coerceAtMost(n - 1)
            val i1 = max(i0 + 1, ((x + 1).toLong() * n / wi).toInt()).coerceAtMost(n)
            var m = 0f
            for (i in i0 until i1) if (c.peaks[i] > m) m = c.peaks[i]
            val a = m * gain
            val hh = min(1f, a) * h / 2
            drawLine(if (a > 1f) Hot else Blue, Offset(x.toFloat(), h / 2 - hh), Offset(x.toFloat(), h / 2 + max(hh, 1f)), 1f)
        }
        sel?.let { drawRect(Color(0x404DA3FF), Offset(it.first * w, 0f), Size((it.second - it.first) * w, h)) }
        drawLine(Color.White, Offset(head * w, 0f), Offset(head * w, h), 3f)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cl by remember { mutableStateOf<Clip?>(null) }
    var undo by remember { mutableStateOf<Clip?>(null) }
    var gain by remember { mutableStateOf(1f) }
    var lim by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var cursor by remember { mutableStateOf(0f) }
    var pos by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("Abre un audio para empezar.") }
    var name by remember { mutableStateOf("audio") }
    var player by remember { mutableStateOf<Player?>(null) }
    val gS = rememberUpdatedState(gain)
    val lS = rememberUpdatedState(lim)

    fun stopP() { player?.stop(); player = null; playing = false }

    fun play() {
        val c = cl ?: return
        stopP()
        val a = ((sel?.first ?: cursor) * c.frames).toLong()
        val b = ((sel?.second ?: 1f) * c.frames).toLong()
        val p = Player(c, { gS.value }, { lS.value }, { f -> pos = f.toFloat() / c.frames }, { playing = false })
        player = p; playing = true; pos = a.toFloat() / c.frames
        p.start(a, b)
    }

    fun work(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true; msg = label
            try { block() } catch (e: Exception) { msg = "No se pudo completar: " + (e.message ?: "error") } finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) work("Decodificando…") {
            stopP(); cl = null; undo = null
            ctx.cacheDir.listFiles()?.forEach { it.delete() }
            val out = File(ctx.cacheDir, "a.pcm")
            val c = withContext(Dispatchers.IO) { decode(ctx, uri, out) { p -> msg = "Decodificando… ${(p * 100).toInt()}%" } }
            cl = c; sel = null; cursor = 0f; pos = 0f; gain = 1f
            name = (uri.lastPathSegment ?: "audio").substringAfterLast('/').substringBeforeLast('.').ifBlank { "audio" }
            msg = "Listo: ${fmt(c.frames, c.sr)} · ${c.ch} canal(es) · ${c.sr} Hz"
        }
    }

    fun edit(cut: Boolean) {
        val c = cl ?: return
        val s = sel ?: run { msg = "Primero selecciona un tramo arrastrando sobre la onda."; return }
        val a = (s.first * c.frames).toLong(); val b = (s.second * c.frames).toLong()
        if (b - a < 1 || (cut && a <= 0L && b >= c.frames)) { msg = "Esa selección no se puede aplicar."; return }
        work("Aplicando…") {
            stopP()
            val out = File(ctx.cacheDir, "e${System.nanoTime()}.pcm")
            val n = withContext(Dispatchers.IO) { if (cut) cutClip(c, a, b, out) else trimClip(c, a, b, out) }
            val old = undo; undo = c; old?.file?.delete()
            cl = n; sel = null; cursor = 0f; pos = 0f
            msg = "Listo: ${fmt(n.frames, n.sr)}"
        }
    }

    fun export(m4a: Boolean) {
        val c = cl ?: return
        stopP()
        work("Exportando…") {
            val nm = name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + "-alto"
            val g = gain; val l = lim
            withContext(Dispatchers.IO) {
                val pr = { p: Float -> msg = "Exportando… ${(p * 100).toInt()}%" }
                if (m4a) exportM4a(ctx, c, g, l, nm, pr) else exportWav(ctx, c, g, l, nm, pr)
            }
            msg = "Guardado en Música/Alto como ${if (m4a) "M4A" else "WAV"}."
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo()
            Column { Text("Alto", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Sube el volumen de tu audio", fontSize = 13.sp, color = Color(0xFF8AA0C8)) }
        }
        Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Abrir audio") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(msg, color = Color(0xFF8AA0C8))

        val c = cl
        if (c != null) {
            Wave(c, gain, sel, if (playing) pos else cursor,
                onTap = { t -> sel = null; cursor = t; if (playing) play() },
                onSel = { a, b -> sel = a to b })
            Text("${fmt((if (playing) pos else cursor).times(c.frames).toLong(), c.sr)} / ${fmt(c.frames, c.sr)}", color = Color(0xFF8AA0C8))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (playing) stopP() else play() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (playing) "Pausar" else "Reproducir") }
            }
            Surface(color = Navy, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Volumen ${(gain * 100).toInt()}%", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Slider(value = gain, onValueChange = { gain = it }, valueRange = 0f..8f)
                    if (c.peak * gain > 1f) Text(if (lim) "Hay picos sobre el máximo: el limitador los controla." else "Cuidado: el audio se distorsionará.", color = Hot)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { if (c.peak > 0f) gain = min(8f, 0.891f / c.peak) }, modifier = Modifier.weight(1f)) { Text("Normalizar") }
                        OutlinedButton(onClick = { gain = 1f }, modifier = Modifier.weight(1f)) { Text("100%") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = lim, onCheckedChange = { lim = it }); Spacer(Modifier.width(8.dp)); Text("Limitador (evita la distorsión)") }
                }
            }
            Surface(color = Navy, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Edición", fontWeight = FontWeight.Bold)
                    Text("Arrastra sobre la onda para seleccionar un tramo.", fontSize = 13.sp, color = Color(0xFF8AA0C8))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { edit(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Recortar") }
                        OutlinedButton(onClick = { edit(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Borrar") }
                        OutlinedButton(onClick = {
                            val u = undo
                            if (u != null) { stopP(); cl?.file?.delete(); cl = u; undo = null; sel = null; cursor = 0f; pos = 0f }
                        }, enabled = !busy && undo != null, modifier = Modifier.weight(1f)) { Text("Deshacer") }
                    }
                }
            }
            Surface(color = Navy, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Exportar con el volumen aplicado", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { export(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("M4A (liviano)") }
                        Button(onClick = { export(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("WAV") }
                    }
                    Text("Se guardan en la carpeta Música/Alto de tu celular.", fontSize = 13.sp, color = Color(0xFF8AA0C8))
                }
            }
        }
    }
}
