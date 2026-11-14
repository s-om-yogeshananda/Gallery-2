package com.yogesh.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One detected face. [vec] is a small descriptor (facial-landmark geometry + a tiny normalised grey crop).
 * A cropped face picture is kept on disk so the People screen can show every person's face.
 */
class FaceRec(val mediaId: Long, val idx: Int, val vec: FloatArray)

class Person(val faces: List<FaceRec>) {
    val mediaIds: List<Long> = faces.map { it.mediaId }.distinct()
    val cover: FaceRec = faces.first()
}

const val GEO_N = 8
const val APP_SIDE = 16
const val APP_N = APP_SIDE * APP_SIDE
const val VEC_N = GEO_N + APP_N

/**
 * Tuning knobs for the grouping. Smaller MATCH_DISTANCE = stricter (more, purer groups);
 * larger = looser (fewer groups, but different people may get merged).
 */
object FaceTuning {
    const val GEO_WEIGHT = 5f
    const val MATCH_DISTANCE = 0.62f
    const val MIN_FACE_PX = 56
    const val MAX_YAW = 35f
    const val MAX_PITCH = 30f
}

class FaceStore(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("faces_v1", Context.MODE_PRIVATE)
    val dir: File get() = File(ctx.filesDir, "faces").apply { mkdirs() }

    fun fileFor(r: FaceRec) = File(dir, "${r.mediaId}_${r.idx}.jpg")

    fun doneIds(): Set<Long> = p.all.keys.mapNotNull { it.toLongOrNull() }.toSet()

    fun put(mediaId: Long, faces: List<FloatArray>) {
        p.edit().putString(mediaId.toString(), faces.joinToString(";") { v -> v.joinToString(",") { "%.4f".format(it) } }).apply()
    }

    fun putAll(map: Map<Long, List<FloatArray>>) {
        val e = p.edit()
        map.forEach { (id, faces) ->
            e.putString(id.toString(), faces.joinToString(";") { v -> v.joinToString(",") { "%.4f".format(it) } })
        }
        e.apply()
    }

    fun loadAll(): List<FaceRec> {
        val out = ArrayList<FaceRec>()
        for ((k, v) in p.all) {
            val id = k.toLongOrNull() ?: continue
            val str = v as? String ?: continue
            if (str.isEmpty()) continue
            str.split(';').forEachIndexed { i, part ->
                val nums = part.split(',')
                if (nums.size == VEC_N) {
                    val vec = FloatArray(VEC_N) { nums[it].toFloatOrNull() ?: 0f }
                    if (fileFor(FaceRec(id, i, vec)).exists()) out.add(FaceRec(id, i, vec))
                }
            }
        }
        return out
    }
}

object FaceTools {
    private fun rot(p: PointF, c: PointF, rad: Float): PointF {
        val dx = p.x - c.x
        val dy = p.y - c.y
        val cs = cos(rad)
        val sn = sin(rad)
        return PointF(dx * cs - dy * sn, dx * sn + dy * cs)
    }

    /** Builds descriptors for the usable faces of one photo and saves their cropped pictures. */
    fun extract(store: FaceStore, mediaId: Long, bmp: Bitmap, faces: List<Face>): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (f in faces) {
            if (abs(f.headEulerAngleY) > FaceTuning.MAX_YAW || abs(f.headEulerAngleX) > FaceTuning.MAX_PITCH) continue
            val b = f.boundingBox
            val l = b.left.coerceIn(0, bmp.width - 1)
            val t = b.top.coerceIn(0, bmp.height - 1)
            val r = b.right.coerceIn(l + 1, bmp.width)
            val bt = b.bottom.coerceIn(t + 1, bmp.height)
            val w = r - l
            val h = bt - t
            if (w < FaceTuning.MIN_FACE_PX || h < FaceTuning.MIN_FACE_PX) continue

            val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: continue
            val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: continue
            val nose = f.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: continue
            val ml = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: continue
            val mr = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: continue
            val mb = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: continue

            val mid = PointF((le.x + re.x) / 2f, (le.y + re.y) / 2f)
            val roll = -(f.headEulerAngleZ) * (Math.PI.toFloat() / 180f)
            val eye = sqrt((le.x - re.x) * (le.x - re.x) + (le.y - re.y) * (le.y - re.y))
            if (eye < 8f) continue
            val geo = FloatArray(GEO_N)
            listOf(nose, ml, mr, mb).forEachIndexed { i, pt ->
                val q = rot(pt, mid, roll)
                geo[i * 2] = q.x / eye
                geo[i * 2 + 1] = q.y / eye
            }

            val crop = Bitmap.createBitmap(bmp, l, t, w, h)
            val small = Bitmap.createScaledBitmap(crop, APP_SIDE, APP_SIDE, true)
            val px = IntArray(APP_N)
            small.getPixels(px, 0, APP_SIDE, 0, 0, APP_SIDE, APP_SIDE)
            val g = FloatArray(APP_N) { i ->
                val c = px[i]
                (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255))
            }
            val mean = g.average().toFloat()
            var sq = 0f
            for (x in g) sq += (x - mean) * (x - mean)
            val sd = sqrt(sq / APP_N).coerceAtLeast(1f)
            for (i in g.indices) g[i] = (g[i] - mean) / sd / sqrt(APP_N.toFloat())   // unit length

            val vec = FloatArray(VEC_N)
            System.arraycopy(geo, 0, vec, 0, GEO_N)
            System.arraycopy(g, 0, vec, GEO_N, APP_N)

            val idx = out.size
            val thumb = Bitmap.createScaledBitmap(crop, 128, 128, true)
            try {
                File(store.dir, "${mediaId}_$idx.jpg").outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            } catch (e: Exception) {
                continue
            }
            out.add(vec)
        }
        return out
    }

    private fun dist(a: FloatArray, c: FloatArray): Float {
        var geo = 0f
        for (i in 0 until GEO_N) { val d = a[i] - c[i]; geo += d * d }
        var dot = 0f
        for (i in GEO_N until VEC_N) dot += a[i] * c[i]
        return FaceTuning.GEO_WEIGHT * sqrt(geo) + (1f - dot)
    }

    /** Greedy clustering. Two faces from the same photo never end up in the same group. */
    fun cluster(all: List<FaceRec>): List<Person> {
        class C(var centroid: FloatArray, val members: ArrayList<FaceRec>, val photos: HashSet<Long>)

        val cs = ArrayList<C>()
        for (f in all) {
            var best: C? = null
            var bd = Float.MAX_VALUE
            for (c in cs) {
                if (f.mediaId in c.photos) continue
                val d = dist(f.vec, c.centroid)
                if (d < bd) { bd = d; best = c }
            }
            val target = best
            if (target != null && bd < FaceTuning.MATCH_DISTANCE) {
                val n = target.members.size
                val old = target.centroid
                val nc = FloatArray(VEC_N)
                for (k in 0 until VEC_N) nc[k] = (old[k] * n + f.vec[k]) / (n + 1)
                target.centroid = nc
                target.members.add(f)
                target.photos.add(f.mediaId)
            } else {
                cs.add(C(f.vec.copyOf(), arrayListOf(f), hashSetOf(f.mediaId)))
            }
        }
        return cs.map { Person(it.members) }.sortedByDescending { it.mediaIds.size }
    }
}

@Composable
fun PeopleScreen(vm: GalleryVM) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadFaces() }
    val people = vm.people
    val real = remember(people) { people.filter { it.mediaIds.size >= 2 } }
    val others = remember(people) { people.filter { it.mediaIds.size < 2 } }
    GScaffold("People", vm) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val p = vm.scanProgress
            if (p != null) Text(
                "Scanning faces ${p.first} / ${p.second}…",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Color(0xFFE1D7FF), fontSize = 13.sp
            ) else Box(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(CircleShape)
                    .background(GalleryGradient).clickable { vm.scanSmart() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text("Scan new photos", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }

            if (people.isEmpty()) {
                Box(Modifier.weight(1f)) {
                    CenterText(if (vm.facesLoaded) "No faces yet.\nTap “Scan new photos”." else "Loading…")
                }
            } else LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(real.size) { i ->
                    val person = real[i]
                    PersonTile(vm, person.cover, "Person ${i + 1}", person.mediaIds.size) {
                        vm.push(Screen.Collection("Person ${i + 1}", person.mediaIds))
                    }
                }
                if (others.isNotEmpty()) item {
                    val ids = others.flatMap { it.mediaIds }.distinct()
                    PersonTile(vm, others.first().cover, "Other faces", ids.size) {
                        vm.push(Screen.Collection("Other faces", ids))
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonTile(vm: GalleryVM, cover: FaceRec, label: String, count: Int, onClick: () -> Unit) {
    val file = remember(cover.mediaId, cover.idx) { vm.faceStore.fileFor(cover) }
    Column(
        Modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Color(0xFF241E3A)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.size(6.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
        Text("$count photos", fontSize = 11.sp, color = Color(0xFFCCC5E6), textAlign = TextAlign.Center)
    }
}
