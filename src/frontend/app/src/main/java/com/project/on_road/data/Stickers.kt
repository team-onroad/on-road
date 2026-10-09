package com.project.on_road.data

/**
 * 아동 홈 꾸미기 스티커. 미션으로 모은 별로 사서 홈 꾸미기 판에 붙인다.
 * 지금은 이모지로 그리고, 디자인 이미지가 생기면 emoji 대신 이미지 리소스로 바꾸면 된다.
 */
enum class Sticker(val emoji: String, val label: String, val price: Int) {
    STAR("⭐", "별", 1),
    SPARKLE("✨", "반짝이", 1),
    HEART("💖", "하트", 1),
    FLOWER("🌸", "벚꽃", 1),
    SUN("☀️", "해님", 1),
    CLOUD("☁️", "구름", 1),
    STRAWBERRY("🍓", "딸기", 1),
    MUSIC("🎵", "음표", 1),
    MOON("🌙", "달님", 2),
    RAINBOW("🌈", "무지개", 2),
    CLOVER("🍀", "네잎클로버", 2),
    BUTTERFLY("🦋", "나비", 2),
    BALLOON("🎈", "풍선", 2),
    ICECREAM("🍦", "아이스크림", 2),
    CAKE("🎂", "케이크", 3),
    GIFT("🎁", "선물", 3),
    ROCKET("🚀", "로켓", 3),
    CROWN("👑", "왕관", 3),
    CAT("🐱", "고양이", 3),
    RABBIT("🐰", "토끼", 3);

    companion object {
        fun of(id: String): Sticker? = entries.firstOrNull { it.name == id }
    }
}

/** 판에 붙은 스티커. x·y는 판 크기 대비 0~1 (가운데 기준) */
data class PlacedSticker(val sticker: Sticker, val x: Float, val y: Float)

object StickerStore {
    fun placed(session: SessionStore): List<PlacedSticker> =
        session.placedStickersRaw.split(";").mapNotNull { part ->
            val p = part.split(",")
            if (p.size != 3) return@mapNotNull null
            val s = Sticker.of(p[0]) ?: return@mapNotNull null
            val x = p[1].toFloatOrNull() ?: return@mapNotNull null
            val y = p[2].toFloatOrNull() ?: return@mapNotNull null
            PlacedSticker(s, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }

    fun savePlaced(session: SessionStore, list: List<PlacedSticker>) {
        session.placedStickersRaw = list.joinToString(";") { "${it.sticker.name},${it.x},${it.y}" }
    }

    /** 사기: 별이 모자라거나 이미 있으면 false */
    fun buy(session: SessionStore, sticker: Sticker): Boolean {
        if (sticker.name in session.ownedStickers) return false
        if (session.starBalance < sticker.price) return false
        session.starsSpent = session.starsSpent + sticker.price
        session.ownedStickers = session.ownedStickers + sticker.name
        return true
    }
}
