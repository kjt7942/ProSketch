package com.prosketch.app.engine

import android.graphics.Bitmap
import android.graphics.Rect

/** Everything an undo entry may need to touch. Implemented by the editor. */
interface HistoryHost {
    val doc: Document
    fun layerPixelsChanged(layer: Layer)
    fun structureChanged()
    fun setSelectionMask(mask: Bitmap?)
    fun documentResized()
}

abstract class HistoryEntry(val label: String) {
    abstract val bytes: Long
    abstract fun undo(h: HistoryHost)
    abstract fun redo(h: HistoryHost)
    open fun dispose() {}
}

private fun Bitmap.cost(): Long = allocationByteCount.toLong()

/** Region of one layer before/after an edit. */
class PixelEntry(label: String, val layer: Layer, val rect: Rect, val before: Bitmap, val after: Bitmap) : HistoryEntry(label) {
    override val bytes get() = before.cost() + after.cost()
    override fun undo(h: HistoryHost) { Bitmaps.paste(layer.bitmap, before, rect.left, rect.top); h.layerPixelsChanged(layer) }
    override fun redo(h: HistoryHost) { Bitmaps.paste(layer.bitmap, after, rect.left, rect.top); h.layerPixelsChanged(layer) }
}

/** Snapshot of the layer stack: order, group membership and masks (layer objects are kept alive by the entry). */
class StructureEntry(
    label: String,
    private val before: List<Layer>, private val beforeActive: Int, private val beforeTree: Map<Layer, TreeState>,
    private val after: List<Layer>, private val afterActive: Int, private val afterTree: Map<Layer, TreeState>,
) : HistoryEntry(label) {
    override val bytes: Long get() {
        fun owned(list: List<Layer>, tree: Map<Layer, TreeState>) = list.flatMap { listOfNotNull(it, tree[it]?.mask) }.toSet()
        val a = owned(after, afterTree); val b = owned(before, beforeTree)
        return b.filter { it !in a }.sumOf { it.bitmap.cost() } + a.filter { it !in b }.sumOf { it.bitmap.cost() }
    }
    private fun apply(h: HistoryHost, list: List<Layer>, active: Int, tree: Map<Layer, TreeState>) {
        h.doc.layers.clear(); h.doc.layers.addAll(list)
        h.doc.applyTreeState(tree)
        h.doc.activeIndex = active.coerceIn(0, list.size - 1)
        if (h.doc.activeLayer?.mask == null) h.doc.editMask = false
        list.forEach { it.touch(); it.mask?.touch() }
        h.structureChanged()
    }
    override fun undo(h: HistoryHost) = apply(h, before, beforeActive, beforeTree)
    override fun redo(h: HistoryHost) = apply(h, after, afterActive, afterTree)
}

/** Text layer edit: spec and pixels before/after. */
class TextEntry(label: String, val layer: Layer, val before: TextSpec?, val beforePx: Bitmap, val after: TextSpec?, val afterPx: Bitmap) : HistoryEntry(label) {
    override val bytes get() = beforePx.cost() + afterPx.cost()
    override fun undo(h: HistoryHost) { layer.text = before; Bitmaps.paste(layer.bitmap, beforePx, 0, 0); h.layerPixelsChanged(layer); h.structureChanged() }
    override fun redo(h: HistoryHost) { layer.text = after; Bitmaps.paste(layer.bitmap, afterPx, 0, 0); h.layerPixelsChanged(layer); h.structureChanged() }
}

class PropsEntry(label: String, val layer: Layer, val before: LayerProps, val after: LayerProps) : HistoryEntry(label) {
    override val bytes = 64L
    override fun undo(h: HistoryHost) { layer.applyProps(before); h.structureChanged() }
    override fun redo(h: HistoryHost) { layer.applyProps(after); h.structureChanged() }
}

class BackgroundEntry(val beforeColor: Int, val beforeVisible: Boolean, val afterColor: Int, val afterVisible: Boolean) : HistoryEntry("배경") {
    override val bytes = 32L
    override fun undo(h: HistoryHost) { h.doc.backgroundColor = beforeColor; h.doc.backgroundVisible = beforeVisible; h.doc.touchBackground(); h.structureChanged() }
    override fun redo(h: HistoryHost) { h.doc.backgroundColor = afterColor; h.doc.backgroundVisible = afterVisible; h.doc.touchBackground(); h.structureChanged() }
}

class SelectionEntry(label: String, val before: Bitmap?, val after: Bitmap?) : HistoryEntry(label) {
    override val bytes get() = (before?.cost() ?: 0L) + (after?.cost() ?: 0L)
    override fun undo(h: HistoryHost) = h.setSelectionMask(before?.copy(Bitmap.Config.ALPHA_8, true))
    override fun redo(h: HistoryHost) = h.setSelectionMask(after?.copy(Bitmap.Config.ALPHA_8, true))
}

/** Whole-document change (resize, crop, flip): bitmaps of every layer are swapped. */
class DocumentEntry(
    label: String,
    val beforeSize: Pair<Int, Int>, val beforeBitmaps: Map<Layer, Bitmap>,
    val afterSize: Pair<Int, Int>, val afterBitmaps: Map<Layer, Bitmap>,
    val beforeText: Map<Layer, TextSpec?> = emptyMap(), val afterText: Map<Layer, TextSpec?> = emptyMap(),
) : HistoryEntry(label) {
    override val bytes get() = beforeBitmaps.values.sumOf { it.cost() } + afterBitmaps.values.sumOf { it.cost() }
    private fun apply(h: HistoryHost, size: Pair<Int, Int>, bm: Map<Layer, Bitmap>, text: Map<Layer, TextSpec?>) {
        h.doc.width = size.first; h.doc.height = size.second
        for ((l, b) in bm) { l.bitmap = b; l.touch() }
        for ((l, t) in text) l.text = t
        h.setSelectionMask(null)
        h.documentResized()
    }
    override fun undo(h: HistoryHost) = apply(h, beforeSize, beforeBitmaps, beforeText)
    override fun redo(h: HistoryHost) = apply(h, afterSize, afterBitmaps, afterText)
}

class CompoundEntry(label: String, val parts: List<HistoryEntry>) : HistoryEntry(label) {
    override val bytes get() = parts.sumOf { it.bytes }
    override fun undo(h: HistoryHost) { for (p in parts.asReversed()) p.undo(h) }
    override fun redo(h: HistoryHost) { for (p in parts) p.redo(h) }
}

class History(private val host: HistoryHost, private val budgetBytes: Long, private val maxSteps: Int = 250) {
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    var listener: (() -> Unit)? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val size get() = undoStack.size

    fun push(e: HistoryEntry) {
        undoStack.addLast(e)
        redoStack.clear()
        trim()
        listener?.invoke()
    }

    fun undo(): String? {
        val e = undoStack.removeLastOrNull() ?: return null
        e.undo(host)
        redoStack.addLast(e)
        listener?.invoke()
        return e.label
    }

    fun redo(): String? {
        val e = redoStack.removeLastOrNull() ?: return null
        e.redo(host)
        undoStack.addLast(e)
        listener?.invoke()
        return e.label
    }

    fun clear() { undoStack.clear(); redoStack.clear(); listener?.invoke() }

    private fun trim() {
        var total = undoStack.sumOf { it.bytes }
        while (undoStack.size > 1 && (total > budgetBytes || undoStack.size > maxSteps)) {
            val e = undoStack.removeFirst()
            total -= e.bytes
            e.dispose()
        }
    }
}
