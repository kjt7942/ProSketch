package com.prosketch.app.engine

import org.json.JSONObject

enum class TipShape(val label: String) {
    ROUND("원형"), PENCIL("연필"), CHARCOAL("목탄"), BRISTLE("강모"), FLAT("평붓"),
    SPLATTER("스플래터"), SQUARE("사각"), SPRAY("스프레이"), LEAF("잎"), STAR("별"), CUSTOM("사용자 지정")
}

enum class GrainType(val label: String) {
    NONE("없음"), PAPER("종이"), CANVAS("캔버스"), PENCIL("연필 결"), SPONGE("스펀지"),
    NOISE("노이즈"), WATERCOLOR("수채"), CHALK("분필"), CUSTOM("사용자 지정")
}

/**
 * Every brush parameter. Section names follow the Brush Studio layout
 * (Stroke Path, Stabilization, Taper, Shape, Grain, Rendering, Wet Mix,
 * Color Dynamics, Dynamics, S Pen, Properties).
 */
data class Brush(
    val id: String,
    var name: String,
    var category: String,
    var builtIn: Boolean = false,

    // Stroke Path
    var spacing: Float = 0.08f,          // fraction of diameter
    var spacingJitter: Float = 0f,
    var jitterLateral: Float = 0f,       // fraction of diameter
    var jitterLinear: Float = 0f,
    var fallOff: Float = 0f,             // fade out over stroke length

    // Stabilization
    var streamline: Float = 0.25f,
    var stabilization: Float = 0f,
    var motionFilter: Float = 0f,

    // Taper
    var taperStart: Float = 0f,          // 0..1 (fraction of max taper length)
    var taperEnd: Float = 0f,
    var taperSize: Float = 0.1f,         // minimum size at the very tip
    var taperOpacity: Float = 0f,
    var taperFinger: Boolean = true,     // apply taper to finger strokes (touch taper)

    // Shape
    var tip: TipShape = TipShape.ROUND,
    var tipHardness: Float = 1f,
    var roundness: Float = 1f,
    var tipAngle: Float = 0f,            // degrees
    var rotation: Float = 0f,            // -1..1, 1 = follow stroke direction
    var scatter: Float = 0f,             // random angle 0..1 (x360)
    var count: Int = 1,
    var countJitter: Float = 0f,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
    var randomized: Boolean = false,
    var pressureRoundness: Float = 0f,
    var tiltRoundness: Float = 0f,
    var customTip: String? = null,

    // Grain
    var grain: GrainType = GrainType.NONE,
    var grainScale: Float = 1f,
    var grainDepth: Float = 1f,
    var grainContrast: Float = 0f,
    var customGrain: String? = null,

    // Rendering
    var flow: Float = 1f,
    var strokeBlend: LayerBlend = LayerBlend.NORMAL,
    var glaze: Boolean = true,           // true = stroke-buffered (glaze), false = direct blending
    var wetEdges: Float = 0f,

    // Wet Mix
    var dilution: Float = 0f,
    var charge: Float = 1f,
    var pull: Float = 0f,

    // Color dynamics
    var stampHue: Float = 0f,
    var stampSat: Float = 0f,
    var stampBri: Float = 0f,
    var strokeHue: Float = 0f,
    var strokeSat: Float = 0f,
    var strokeBri: Float = 0f,

    // Dynamics
    var speedSize: Float = 0f,           // -1..1
    var speedOpacity: Float = 0f,
    var jitterSize: Float = 0f,
    var jitterOpacity: Float = 0f,

    // S Pen
    var pressureSize: Float = 0.7f,
    var pressureOpacity: Float = 0.2f,
    var pressureFlow: Float = 0f,
    var tiltSize: Float = 0f,
    var tiltOpacity: Float = 0f,
    var tiltAngleDeg: Float = 30f,       // tilt trigger angle
    var azimuth: Boolean = false,        // rotate tip with pen orientation

    // Properties
    var sizeMin: Float = 1f,
    var sizeMax: Float = 80f,
    var opacityMin: Float = 0f,
    var opacityMax: Float = 1f,
    var defaultSize: Float = 0.35f,      // 0..1 slider value
    var defaultOpacity: Float = 1f,
    var smudgePull: Float = 0.8f,
) {
    fun sizeFor(slider: Float): Float {
        val s = slider.coerceIn(0f, 1f)
        return sizeMin + (sizeMax - sizeMin) * s * kotlin.math.sqrt(s)
    }

    fun opacityFor(slider: Float): Float = opacityMin + (opacityMax - opacityMin) * slider.coerceIn(0f, 1f)

    fun duplicate(newId: String, newName: String) = copy(id = newId, name = newName, builtIn = false)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("category", category)
        put("spacing", spacing); put("spacingJitter", spacingJitter); put("jitterLateral", jitterLateral)
        put("jitterLinear", jitterLinear); put("fallOff", fallOff)
        put("streamline", streamline); put("stabilization", stabilization); put("motionFilter", motionFilter)
        put("taperStart", taperStart); put("taperEnd", taperEnd); put("taperSize", taperSize)
        put("taperOpacity", taperOpacity); put("taperFinger", taperFinger)
        put("tip", tip.name); put("tipHardness", tipHardness); put("roundness", roundness); put("tipAngle", tipAngle)
        put("rotation", rotation); put("scatter", scatter); put("count", count); put("countJitter", countJitter)
        put("flipX", flipX); put("flipY", flipY); put("randomized", randomized)
        put("pressureRoundness", pressureRoundness); put("tiltRoundness", tiltRoundness)
        customTip?.let { put("customTip", it) }
        put("grain", grain.name); put("grainScale", grainScale); put("grainDepth", grainDepth); put("grainContrast", grainContrast)
        customGrain?.let { put("customGrain", it) }
        put("flow", flow); put("strokeBlend", strokeBlend.name); put("glaze", glaze); put("wetEdges", wetEdges)
        put("dilution", dilution); put("charge", charge); put("pull", pull)
        put("stampHue", stampHue); put("stampSat", stampSat); put("stampBri", stampBri)
        put("strokeHue", strokeHue); put("strokeSat", strokeSat); put("strokeBri", strokeBri)
        put("speedSize", speedSize); put("speedOpacity", speedOpacity); put("jitterSize", jitterSize); put("jitterOpacity", jitterOpacity)
        put("pressureSize", pressureSize); put("pressureOpacity", pressureOpacity); put("pressureFlow", pressureFlow)
        put("tiltSize", tiltSize); put("tiltOpacity", tiltOpacity); put("tiltAngleDeg", tiltAngleDeg); put("azimuth", azimuth)
        put("sizeMin", sizeMin); put("sizeMax", sizeMax); put("opacityMin", opacityMin); put("opacityMax", opacityMax)
        put("defaultSize", defaultSize); put("defaultOpacity", defaultOpacity); put("smudgePull", smudgePull)
    }

    companion object {
        fun fromJson(o: JSONObject): Brush {
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            val b = Brush(o.getString("id"), o.optString("name", "브러시"), o.optString("category", "나의 브러시"))
            return b.apply {
                spacing = f("spacing", spacing); spacingJitter = f("spacingJitter", spacingJitter)
                jitterLateral = f("jitterLateral", jitterLateral); jitterLinear = f("jitterLinear", jitterLinear)
                fallOff = f("fallOff", fallOff)
                streamline = f("streamline", streamline); stabilization = f("stabilization", stabilization)
                motionFilter = f("motionFilter", motionFilter)
                taperStart = f("taperStart", taperStart); taperEnd = f("taperEnd", taperEnd)
                taperSize = f("taperSize", taperSize); taperOpacity = f("taperOpacity", taperOpacity)
                taperFinger = o.optBoolean("taperFinger", taperFinger)
                tip = runCatching { TipShape.valueOf(o.optString("tip")) }.getOrDefault(TipShape.ROUND)
                tipHardness = f("tipHardness", tipHardness); roundness = f("roundness", roundness)
                tipAngle = f("tipAngle", tipAngle); rotation = f("rotation", rotation); scatter = f("scatter", scatter)
                count = o.optInt("count", count); countJitter = f("countJitter", countJitter)
                flipX = o.optBoolean("flipX"); flipY = o.optBoolean("flipY"); randomized = o.optBoolean("randomized")
                pressureRoundness = f("pressureRoundness", pressureRoundness); tiltRoundness = f("tiltRoundness", tiltRoundness)
                customTip = o.optString("customTip").ifEmpty { null }
                grain = runCatching { GrainType.valueOf(o.optString("grain")) }.getOrDefault(GrainType.NONE)
                grainScale = f("grainScale", grainScale); grainDepth = f("grainDepth", grainDepth)
                grainContrast = f("grainContrast", grainContrast)
                customGrain = o.optString("customGrain").ifEmpty { null }
                flow = f("flow", flow); strokeBlend = LayerBlend.fromName(o.optString("strokeBlend"))
                glaze = o.optBoolean("glaze", true); wetEdges = f("wetEdges", wetEdges)
                dilution = f("dilution", dilution); charge = f("charge", charge); pull = f("pull", pull)
                stampHue = f("stampHue", 0f); stampSat = f("stampSat", 0f); stampBri = f("stampBri", 0f)
                strokeHue = f("strokeHue", 0f); strokeSat = f("strokeSat", 0f); strokeBri = f("strokeBri", 0f)
                speedSize = f("speedSize", 0f); speedOpacity = f("speedOpacity", 0f)
                jitterSize = f("jitterSize", 0f); jitterOpacity = f("jitterOpacity", 0f)
                pressureSize = f("pressureSize", pressureSize); pressureOpacity = f("pressureOpacity", pressureOpacity)
                pressureFlow = f("pressureFlow", pressureFlow); tiltSize = f("tiltSize", 0f); tiltOpacity = f("tiltOpacity", 0f)
                tiltAngleDeg = f("tiltAngleDeg", tiltAngleDeg); azimuth = o.optBoolean("azimuth")
                sizeMin = f("sizeMin", sizeMin); sizeMax = f("sizeMax", sizeMax)
                opacityMin = f("opacityMin", opacityMin); opacityMax = f("opacityMax", opacityMax)
                defaultSize = f("defaultSize", defaultSize); defaultOpacity = f("defaultOpacity", defaultOpacity)
                smudgePull = f("smudgePull", smudgePull)
            }
        }
    }
}

/** The built-in brush library. */
object DefaultBrushes {
    const val SKETCH = "스케치"
    const val INK = "잉크"
    const val PAINT = "페인팅"
    const val AIR = "에어브러시"
    const val TEXTURE = "텍스처"
    const val CALLI = "캘리그래피"
    const val ARTISTIC = "아티스틱"
    const val CUSTOM = "나의 브러시"

    val categories = listOf(SKETCH, INK, PAINT, AIR, CALLI, TEXTURE, ARTISTIC)

    fun all(): List<Brush> = listOf(
        // ---- Sketching
        Brush("b_6b", "6B 연필", SKETCH, true, spacing = 0.06f, streamline = 0.1f, tip = TipShape.PENCIL, tipHardness = 0.6f,
            grain = GrainType.PENCIL, grainScale = 0.8f, grainDepth = 0.9f, flow = 0.75f, pressureSize = 0.35f, pressureOpacity = 0.85f,
            tiltSize = 0.8f, tiltOpacity = 0.45f, sizeMin = 2f, sizeMax = 40f, defaultSize = 0.4f, taperStart = 0.1f, taperEnd = 0.1f, taperSize = 0.4f),
        Brush("b_hb", "HB 연필", SKETCH, true, spacing = 0.05f, streamline = 0.15f, tip = TipShape.PENCIL, tipHardness = 0.8f,
            grain = GrainType.PENCIL, grainScale = 0.6f, grainDepth = 0.7f, flow = 0.6f, pressureSize = 0.25f, pressureOpacity = 0.9f,
            tiltSize = 0.6f, tiltOpacity = 0.3f, sizeMin = 1f, sizeMax = 18f, defaultSize = 0.4f, taperStart = 0.1f, taperEnd = 0.1f, taperSize = 0.5f),
        Brush("b_narinder", "나린더 연필", SKETCH, true, spacing = 0.04f, streamline = 0.2f, tip = TipShape.PENCIL, tipHardness = 0.9f,
            grain = GrainType.PAPER, grainScale = 0.6f, grainDepth = 0.55f, flow = 0.85f, pressureSize = 0.5f, pressureOpacity = 0.6f,
            sizeMin = 1f, sizeMax = 14f, defaultSize = 0.45f, taperStart = 0.15f, taperEnd = 0.2f, taperSize = 0.2f),
        Brush("b_peppermint", "페퍼민트", SKETCH, true, spacing = 0.03f, streamline = 0.35f, tip = TipShape.ROUND, tipHardness = 0.85f,
            grain = GrainType.PAPER, grainScale = 0.5f, grainDepth = 0.35f, flow = 0.9f, pressureSize = 0.6f, pressureOpacity = 0.5f,
            sizeMin = 1f, sizeMax = 12f, defaultSize = 0.4f, taperStart = 0.2f, taperEnd = 0.25f, taperSize = 0.05f),
        Brush("b_charcoal", "목탄", SKETCH, true, spacing = 0.1f, streamline = 0.05f, tip = TipShape.CHARCOAL, tipHardness = 0.5f,
            scatter = 1f, randomized = true, grain = GrainType.CHALK, grainScale = 1.2f, grainDepth = 1f, flow = 0.7f,
            pressureSize = 0.4f, pressureOpacity = 0.7f, tiltSize = 1f, tiltOpacity = 0.4f, sizeMin = 4f, sizeMax = 90f, defaultSize = 0.4f),

        // ---- Inking
        Brush("b_studio", "스튜디오 펜", INK, true, spacing = 0.02f, streamline = 0.45f, tip = TipShape.ROUND, tipHardness = 1f,
            flow = 1f, pressureSize = 0.85f, pressureOpacity = 0f, sizeMin = 1f, sizeMax = 40f, defaultSize = 0.35f,
            taperStart = 0.25f, taperEnd = 0.35f, taperSize = 0.02f, speedSize = -0.15f),
        Brush("b_tech", "기술용 펜", INK, true, spacing = 0.02f, streamline = 0.5f, tip = TipShape.ROUND, tipHardness = 1f,
            flow = 1f, pressureSize = 0f, pressureOpacity = 0f, sizeMin = 1f, sizeMax = 30f, defaultSize = 0.25f),
        Brush("b_gel", "젤 펜", INK, true, spacing = 0.02f, streamline = 0.3f, tip = TipShape.ROUND, tipHardness = 0.95f,
            flow = 1f, pressureSize = 0.45f, sizeMin = 1f, sizeMax = 25f, defaultSize = 0.3f, taperStart = 0.1f, taperEnd = 0.15f, taperSize = 0.3f),
        Brush("b_dryink", "드라이 잉크", INK, true, spacing = 0.04f, streamline = 0.25f, tip = TipShape.CHARCOAL, tipHardness = 0.9f,
            grain = GrainType.PAPER, grainScale = 0.7f, grainDepth = 0.8f, grainContrast = 0.6f, flow = 1f, pressureSize = 0.7f,
            pressureOpacity = 0.1f, sizeMin = 2f, sizeMax = 45f, defaultSize = 0.35f, taperStart = 0.15f, taperEnd = 0.2f, taperSize = 0.1f),
        Brush("b_marker", "마커", INK, true, spacing = 0.03f, streamline = 0.2f, tip = TipShape.FLAT, roundness = 0.55f, tipAngle = 35f,
            tipHardness = 0.9f, flow = 0.55f, pressureSize = 0.15f, pressureOpacity = 0.2f, sizeMin = 4f, sizeMax = 70f,
            defaultSize = 0.4f, defaultOpacity = 0.85f),

        // ---- Painting
        Brush("b_round", "둥근 붓", PAINT, true, spacing = 0.05f, streamline = 0.15f, tip = TipShape.BRISTLE, tipHardness = 0.8f,
            grain = GrainType.CANVAS, grainScale = 1f, grainDepth = 0.3f, flow = 0.85f, pressureSize = 0.75f, pressureOpacity = 0.35f,
            dilution = 0.05f, pull = 0.15f, charge = 0.85f, sizeMin = 3f, sizeMax = 120f, defaultSize = 0.4f,
            taperStart = 0.1f, taperEnd = 0.2f, taperSize = 0.2f, rotation = 1f),
        Brush("b_flat", "평붓", PAINT, true, spacing = 0.04f, streamline = 0.1f, tip = TipShape.FLAT, roundness = 0.3f, tipHardness = 0.75f,
            grain = GrainType.CANVAS, grainDepth = 0.35f, flow = 0.9f, pressureSize = 0.5f, pressureOpacity = 0.4f, pull = 0.25f,
            charge = 0.8f, sizeMin = 5f, sizeMax = 150f, defaultSize = 0.4f, rotation = 1f),
        Brush("b_gouache", "과슈", PAINT, true, spacing = 0.05f, streamline = 0.1f, tip = TipShape.CHARCOAL, tipHardness = 0.85f,
            grain = GrainType.PAPER, grainScale = 1.1f, grainDepth = 0.55f, flow = 0.9f, pressureSize = 0.4f, pressureOpacity = 0.6f,
            pull = 0.1f, sizeMin = 4f, sizeMax = 140f, defaultSize = 0.45f, rotation = 0.6f),
        Brush("b_oil", "유화", PAINT, true, spacing = 0.04f, streamline = 0.1f, tip = TipShape.BRISTLE, tipHardness = 0.85f,
            grain = GrainType.CANVAS, grainScale = 1.2f, grainDepth = 0.45f, flow = 1f, glaze = false, pressureSize = 0.6f,
            pressureOpacity = 0.3f, pull = 0.55f, charge = 0.6f, dilution = 0f, sizeMin = 5f, sizeMax = 160f, defaultSize = 0.45f, rotation = 1f),
        Brush("b_water", "수채", PAINT, true, spacing = 0.04f, streamline = 0.2f, tip = TipShape.ROUND, tipHardness = 0.25f,
            grain = GrainType.WATERCOLOR, grainScale = 1.4f, grainDepth = 0.6f, flow = 0.35f, wetEdges = 0.5f,
            pressureSize = 0.45f, pressureOpacity = 0.6f, dilution = 0.35f, pull = 0.25f, sizeMin = 8f, sizeMax = 220f,
            defaultSize = 0.45f, defaultOpacity = 0.7f, stampBri = 0.03f),
        Brush("b_wetacrylic", "습식 아크릴", PAINT, true, spacing = 0.035f, streamline = 0.15f, tip = TipShape.ROUND, tipHardness = 0.7f,
            grain = GrainType.CANVAS, grainDepth = 0.25f, flow = 1f, glaze = false, pull = 0.7f, charge = 0.45f, pressureSize = 0.5f,
            pressureOpacity = 0.4f, sizeMin = 6f, sizeMax = 180f, defaultSize = 0.4f),

        // ---- Airbrushing
        Brush("b_soft_air", "소프트 에어브러시", AIR, true, spacing = 0.05f, streamline = 0.1f, tip = TipShape.ROUND, tipHardness = 0f,
            flow = 0.18f, pressureSize = 0.2f, pressureOpacity = 0.8f, sizeMin = 10f, sizeMax = 600f, defaultSize = 0.5f, defaultOpacity = 0.9f),
        Brush("b_med_air", "중간 에어브러시", AIR, true, spacing = 0.04f, streamline = 0.1f, tip = TipShape.ROUND, tipHardness = 0.45f,
            flow = 0.3f, pressureSize = 0.3f, pressureOpacity = 0.7f, sizeMin = 6f, sizeMax = 400f, defaultSize = 0.45f),
        Brush("b_hard_air", "하드 에어브러시", AIR, true, spacing = 0.03f, streamline = 0.15f, tip = TipShape.ROUND, tipHardness = 0.9f,
            flow = 0.85f, pressureSize = 0.3f, pressureOpacity = 0.5f, sizeMin = 4f, sizeMax = 400f, defaultSize = 0.4f),
        Brush("b_spray", "스프레이 페인트", AIR, true, spacing = 0.05f, streamline = 0f, tip = TipShape.SPRAY, tipHardness = 0.5f,
            scatter = 1f, randomized = true, flow = 0.5f, pressureSize = 0.4f, pressureOpacity = 0.7f, sizeMin = 10f, sizeMax = 400f, defaultSize = 0.45f),

        // ---- Calligraphy
        Brush("b_chisel", "끌 펜", CALLI, true, spacing = 0.015f, streamline = 0.35f, tip = TipShape.FLAT, roundness = 0.12f, tipAngle = 45f,
            tipHardness = 1f, flow = 1f, pressureSize = 0.25f, sizeMin = 4f, sizeMax = 80f, defaultSize = 0.35f),
        Brush("b_brushpen", "브러시 펜", CALLI, true, spacing = 0.015f, streamline = 0.5f, tip = TipShape.ROUND, tipHardness = 1f,
            flow = 1f, pressureSize = 1f, sizeMin = 1f, sizeMax = 50f, defaultSize = 0.4f, taperStart = 0.3f, taperEnd = 0.4f, taperSize = 0.01f),
        Brush("b_monoline", "모노라인", CALLI, true, spacing = 0.015f, streamline = 0.65f, tip = TipShape.ROUND, tipHardness = 1f,
            flow = 1f, pressureSize = 0f, pressureOpacity = 0f, sizeMin = 2f, sizeMax = 60f, defaultSize = 0.3f),
        Brush("b_script", "스크립트", CALLI, true, spacing = 0.015f, streamline = 0.55f, tip = TipShape.FLAT, roundness = 0.35f, tipAngle = 30f,
            tipHardness = 1f, flow = 1f, pressureSize = 0.8f, sizeMin = 2f, sizeMax = 60f, defaultSize = 0.35f, taperStart = 0.2f, taperEnd = 0.3f),

        // ---- Textures
        Brush("b_noise", "노이즈 브러시", TEXTURE, true, spacing = 0.12f, streamline = 0f, tip = TipShape.ROUND, tipHardness = 0.3f,
            grain = GrainType.NOISE, grainScale = 1f, grainDepth = 1f, grainContrast = 0.5f, flow = 0.5f, pressureOpacity = 0.6f,
            sizeMin = 20f, sizeMax = 600f, defaultSize = 0.5f),
        Brush("b_sponge", "스펀지", TEXTURE, true, spacing = 0.15f, streamline = 0f, tip = TipShape.CHARCOAL, tipHardness = 0.6f,
            scatter = 1f, randomized = true, grain = GrainType.SPONGE, grainScale = 1f, grainDepth = 1f, flow = 0.6f,
            pressureOpacity = 0.6f, sizeMin = 20f, sizeMax = 400f, defaultSize = 0.45f),
        Brush("b_canvas", "캔버스 질감", TEXTURE, true, spacing = 0.1f, streamline = 0f, tip = TipShape.ROUND, tipHardness = 0.2f,
            grain = GrainType.CANVAS, grainScale = 1.5f, grainDepth = 1f, grainContrast = 0.4f, flow = 0.4f, pressureOpacity = 0.6f,
            sizeMin = 30f, sizeMax = 800f, defaultSize = 0.5f),
        Brush("b_splatter", "스플래터", TEXTURE, true, spacing = 0.6f, spacingJitter = 0.6f, streamline = 0f, tip = TipShape.SPLATTER,
            scatter = 1f, randomized = true, jitterSize = 0.5f, jitterLateral = 0.4f, flow = 1f, pressureSize = 0.4f, pressureOpacity = 0f,
            sizeMin = 20f, sizeMax = 500f, defaultSize = 0.4f),

        // ---- Artistic
        Brush("b_leaves", "나뭇잎", ARTISTIC, true, spacing = 0.45f, spacingJitter = 0.5f, streamline = 0f, tip = TipShape.LEAF,
            rotation = 1f, scatter = 0.3f, jitterSize = 0.4f, jitterLateral = 0.6f, stampHue = 0.05f, stampBri = 0.15f,
            flow = 1f, pressureSize = 0.5f, sizeMin = 10f, sizeMax = 200f, defaultSize = 0.4f),
        Brush("b_stars", "반짝이", ARTISTIC, true, spacing = 1.2f, spacingJitter = 1f, streamline = 0f, tip = TipShape.STAR,
            scatter = 1f, jitterSize = 0.8f, jitterLateral = 1.5f, jitterOpacity = 0.5f, strokeBlend = LayerBlend.ADD,
            flow = 1f, pressureSize = 0.3f, sizeMin = 6f, sizeMax = 120f, defaultSize = 0.35f),
        Brush("b_glow", "네온 글로우", ARTISTIC, true, spacing = 0.03f, streamline = 0.4f, tip = TipShape.ROUND, tipHardness = 0.15f,
            strokeBlend = LayerBlend.ADD, flow = 0.4f, pressureSize = 0.5f, pressureOpacity = 0.3f, sizeMin = 4f, sizeMax = 200f,
            defaultSize = 0.35f),
        Brush("b_rainbow", "무지개", ARTISTIC, true, spacing = 0.03f, streamline = 0.35f, tip = TipShape.ROUND, tipHardness = 0.9f,
            stampHue = 0.0f, strokeHue = 1f, flow = 1f, pressureSize = 0.6f, sizeMin = 2f, sizeMax = 80f, defaultSize = 0.35f),
    )
}
