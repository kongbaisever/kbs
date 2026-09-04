package kbs.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 常用目标（收藏）。
 *
 * 存到内部存储而非 SharedPreferences：条目数量可能增长，
 * 且含可空的区间字段，用 JSON 表达比塞进字符串集合更清晰。
 *
 * ★ 文件 IO 一律在调用方切到 Dispatchers.IO 后执行，
 *   本类不自己做线程切换，保持纯粹。
 */
data class Favorite(
    val id: String,
    val name: String,
    val x: Double,
    val z: Double,
    /** 该目标的地面高度（不同维度可能不同） */
    val groundHeight: Double = 128.0,
    /** 终点高度下限（地狱模式用） */
    val endHeightMin: Double? = null,
    /** 终点高度上限（地狱模式用） */
    val endHeightMax: Double? = null,
)

object FavoriteStore {

    private const val FILE_NAME = "favorites.json"

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun load(context: Context): List<Favorite> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val out = mutableListOf<Favorite>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Favorite(
                id = o.optString("id"),
                name = o.optString("name"),
                x = o.optDouble("x"),
                z = o.optDouble("z"),
                groundHeight = o.optDouble("groundHeight", 128.0),
                endHeightMin = o.optDoubleOrNull("endHeightMin"),
                endHeightMax = o.optDoubleOrNull("endHeightMax"),
            )
        }
        out
    }.getOrDefault(emptyList())

    fun save(context: Context, list: List<Favorite>) = runCatching {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("x", it.x)
                put("z", it.z)
                put("groundHeight", it.groundHeight)
                if (it.endHeightMin != null) put("endHeightMin", it.endHeightMin)
                if (it.endHeightMax != null) put("endHeightMax", it.endHeightMax)
            })
        }
        file(context).writeText(arr.toString())
    }

    /** org.json 的 optDouble 无"缺失返回 null"版本，这里补一个 */
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null
}
