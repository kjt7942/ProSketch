package com.prosketch.app.data

import com.prosketch.app.L

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ArtStack(val id: String, var name: String, val ids: MutableList<String>)

sealed class GalleryEntry {
    abstract val key: String
    class Art(val info: ArtworkInfo) : GalleryEntry() { override val key get() = info.id }
    class Stack(val stack: ArtStack, val items: List<ArtworkInfo>) : GalleryEntry() { override val key get() = stack.id }
}

/**
 * The gallery's own order and its stacks (Procreate-style), kept in artworks/gallery.json.
 * Artworks it does not know yet (new, imported, duplicated) appear first, newest first.
 */
class GalleryLayout(private val file: File) {
    private val order = mutableListOf<String>()          // top level: artwork ids and stack ids
    private val stacks = linkedMapOf<String, ArtStack>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("order")?.let { a -> for (i in 0 until a.length()) order.add(a.getString(i)) }
            o.optJSONArray("stacks")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    val ids = s.getJSONArray("ids")
                    stacks[s.getString("id")] = ArtStack(s.getString("id"), s.optString("name", L.t("스택")), MutableList(ids.length()) { ids.getString(it) })
                }
            }
        }
    }

    @Synchronized
    fun save() {
        val o = JSONObject()
        o.put("order", JSONArray(order))
        o.put("stacks", JSONArray(stacks.values.map { JSONObject().put("id", it.id).put("name", it.name).put("ids", JSONArray(it.ids)) }))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        file.delete(); tmp.renameTo(file)
    }

    /** Reconciles with the artworks on disk and returns the top level. Stacks of one dissolve. */
    @Synchronized
    fun resolve(all: List<ArtworkInfo>): List<GalleryEntry> {
        val byId = all.associateBy { it.id }
        val before = snapshotKey()
        for (s in stacks.values) s.ids.retainAll { it in byId }
        for (s in stacks.values.toList()) if (s.ids.size <= 1) {
            val at = order.indexOf(s.id)
            stacks.remove(s.id); order.remove(s.id)
            if (s.ids.size == 1) order.add(if (at >= 0) at else 0, s.ids[0])
        }
        val inStack = stacks.values.flatMap { it.ids }.toSet()
        order.retainAll { (it in byId && it !in inStack) || it in stacks }
        val known = order.toSet()
        order.addAll(0, stacks.keys.filter { it !in known })
        order.addAll(0, all.filter { it.id !in known && it.id !in inStack }.sortedByDescending { it.modified }.map { it.id })
        if (snapshotKey() != before) save()
        return order.mapNotNull { k ->
            stacks[k]?.let { s -> GalleryEntry.Stack(s, s.ids.mapNotNull(byId::get)) } ?: byId[k]?.let { GalleryEntry.Art(it) }
        }
    }

    private fun snapshotKey() = order.joinToString() + "|" + stacks.values.joinToString { it.id + it.ids.joinToString() }

    fun stack(id: String): ArtStack? = stacks[id]
    fun stackOf(artId: String): ArtStack? = stacks.values.firstOrNull { artId in it.ids }

    private fun detach(id: String) { order.remove(id); stacks.values.forEach { it.ids.remove(id) } }

    /** Groups [ids] into a new stack placed where the first of them was. */
    @Synchronized
    fun createStack(ids: List<String>, name: String = L.t("스택")): String {
        val at = ids.map { order.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        ids.forEach { detach(it) }
        val s = ArtStack("s_" + UUID.randomUUID().toString().take(8), name, ids.toMutableList())
        stacks[s.id] = s
        order.add(at.coerceAtMost(order.size), s.id)
        save()
        return s.id
    }

    /** Drop [dragged] onto [target]: into the stack, or a new stack of the two artworks. */
    @Synchronized
    fun addTo(target: String, dragged: String) {
        if (target == dragged) return
        val s = stacks[target]
        if (s != null) { detach(dragged); s.ids.add(0, dragged); save(); return }
        val at = order.indexOf(target)
        detach(target); detach(dragged)
        val ns = ArtStack("s_" + UUID.randomUUID().toString().take(8), L.t("스택"), mutableListOf(target, dragged))
        stacks[ns.id] = ns
        order.add(if (at >= 0) at.coerceAtMost(order.size) else 0, ns.id)
        save()
    }

    /** Takes an artwork out of its stack and puts it right after the stack. */
    @Synchronized
    fun takeOut(id: String) {
        val s = stackOf(id) ?: return
        s.ids.remove(id)
        val at = order.indexOf(s.id)
        order.add(if (at >= 0) at + 1 else 0, id)
        save()
    }

    @Synchronized
    fun unstack(stackId: String) {
        val s = stacks.remove(stackId) ?: return
        val at = order.indexOf(stackId).coerceAtLeast(0)
        order.remove(stackId)
        order.addAll(at.coerceAtMost(order.size), s.ids)
        save()
    }

    /** Moves [id] to position [to] within the top level ([stackId] null) or within a stack. */
    @Synchronized
    fun move(stackId: String?, id: String, to: Int) {
        val list = if (stackId == null) order else stacks[stackId]?.ids ?: return
        val from = list.indexOf(id)
        if (from < 0) return
        list.removeAt(from)
        list.add((if (to > from) to - 1 else to).coerceIn(0, list.size), id)
        save()
    }

    @Synchronized
    fun rename(stackId: String, name: String) { stacks[stackId]?.name = name; save() }
}
