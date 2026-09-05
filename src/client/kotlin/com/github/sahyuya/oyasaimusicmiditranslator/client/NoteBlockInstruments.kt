package com.github.sahyuya.oyasaimusicmiditranslator.client

/**
 * 音ブロック16楽器の一覧（サーバー側 InstrumentMapper や web/src/instruments.js と同じ0〜15の並び）。
 *
 * ノートインスペクターの楽器欄を、生の数値入力ではなく選択式(コンボボックス)にするために使う。
 * 配列のインデックスがそのまま [com.github.sahyuya.oyasaimusicmiditranslator.client.EditorNote.instrument]
 * の値(0〜15)と一致するため、ImGuiのコンボボックスへ [ImInt] をそのまま束縛できる。
 */
object NoteBlockInstruments {
    const val OTHER_INDEX = 20
    data class Entry(
        val id: Int,
        val key: String,
        val english: String,
        val japanese: String,
        val blockEnglish: String,
        val blockJapanese: String,
    )

    val ENTRIES: List<Entry> = listOf(
        Entry(0, "piano", "Harp / Piano", "ハープ / ピアノ", "Other blocks (grass, dirt, etc.)", "その他（草・土など）"),
        Entry(1, "bass_guitar", "Bass", "ベース", "Wood", "木材系"),
        Entry(2, "bass_drum", "Bass Drum", "バスドラム", "Stone", "石系"),
        Entry(3, "snare_drum", "Snare", "スネア", "Sand", "砂系"),
        Entry(4, "sticks", "Hi-Hat", "ハイハット", "Glass", "ガラス系"),
        Entry(5, "flute", "Flute", "フルート", "Clay", "粘土"),
        Entry(6, "bell", "Bell", "ベル", "Gold Block", "金ブロック"),
        Entry(7, "guitar", "Guitar", "ギター", "Wool", "羊毛"),
        Entry(8, "chime", "Chime", "チャイム", "Packed Ice", "氷塊"),
        Entry(9, "xylophone", "Xylophone", "木琴", "Bone Block", "骨ブロック"),
        Entry(10, "iron_xylophone", "Iron Xylophone", "鉄琴", "Iron Block", "鉄ブロック"),
        Entry(11, "cow_bell", "Cow Bell", "カウベル", "Soul Sand", "ソウルサンド"),
        Entry(12, "didgeridoo", "Didgeridoo", "ディジュリドゥ", "Pumpkin", "カボチャ"),
        Entry(13, "bit", "Bit", "ビット", "Emerald Block", "エメラルドブロック"),
        Entry(14, "banjo", "Banjo", "バンジョー", "Hay Bale", "干草の俵"),
        Entry(15, "pling", "Pling", "プリング", "Glowstone", "グロウストーン"),
        Entry(16, "trumpet", "Trumpet", "ラッパ", "Copper Block", "銅ブロック"),
        Entry(17, "trumpet_exposed", "Exposed Trumpet", "風化したラッパ", "Exposed Copper", "風化した銅"),
        Entry(18, "trumpet_oxidized", "Oxidized Trumpet", "酸化したラッパ", "Oxidized Copper", "酸化した銅"),
        Entry(19, "trumpet_weathered", "Weathered Trumpet", "錆びたラッパ", "Weathered Copper", "錆びた銅"),
    )

    // The bundled Minecraft font does not contain the em dash on every installation and rendered
    // it as '?'. Keep the separator deliberately ASCII while retaining localized names.
    private val LABELS_JA: Array<String> = (ENTRIES.map { "${it.japanese}  |  ${it.blockJapanese}" } + "その他のMinecraftサウンド").toTypedArray()
    private val LABELS_EN: Array<String> = (ENTRIES.map { "${it.english}  |  ${it.blockEnglish}" } + "Other Minecraft sound").toTypedArray()

    /** コンボボックス表示用の配列。インデックスがそのまま楽器ID(0〜15)と一致する。 */
    fun labels(japanese: Boolean): Array<String> = if (japanese) LABELS_JA else LABELS_EN

    /** パート名など短い表示用。ブロック素材名は含めない。 */
    fun displayName(id: Int, japanese: Boolean = true): String {
        val entry = ENTRIES.getOrNull(id) ?: ENTRIES[0]
        return if (japanese) entry.japanese else entry.english
    }
}
