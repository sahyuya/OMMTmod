package com.github.sahyuya.oyasaimusicmiditranslator.client

import cn.enaium.fabric.imgui.DefaultImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiConfigFlags
import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader

/**
 * OMMT-owned configuration for the Fabric ImGui bridge.
 *
 * ImGui's bundled default font does not contain Japanese glyphs. Prefer a commonly installed CJK
 * font, while retaining a safe default-font fallback for machines without one of these fonts.
 */
class OyasaiImGuiService : DefaultImGui("ommt-imgui-layout") {
  override fun configure(io: ImGuiIO) {
    val atlas = io.fonts
    val japaneseFont = japaneseFontCandidates().firstOrNull(Files::isRegularFile)
    if (japaneseFont != null) {
      atlas.addFontFromFileTTF(japaneseFont.toString(), 18f, atlas.glyphRangesJapanese)
    } else {
      atlas.addFontDefault()
    }
    atlas.build()
    val layout = FabricLoader.getInstance().configDir.resolve("ommt-imgui-layout.ini")
    if (!Files.exists(layout)) runCatching {
      Files.createDirectories(layout.parent)
      OyasaiImGuiService::class.java.getResourceAsStream("/assets/oyasaimusicmiditranslator/default-imgui-layout.ini")?.use { input -> Files.copy(input, layout) }
    }
    io.setIniFilename(layout.toString())
    io.setConfigFlags(ImGuiConfigFlags.DockingEnable)
  }

  private fun japaneseFontCandidates(): List<Path> {
    val windows = System.getenv("WINDIR")?.let(Path::of)
    return buildList {
      if (windows != null) {
        add(windows.resolve("Fonts/YuGothM.ttc"))
        add(windows.resolve("Fonts/YuGothR.ttc"))
        add(windows.resolve("Fonts/meiryo.ttc"))
        add(windows.resolve("Fonts/msgothic.ttc"))
      }
      add(Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
      add(Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"))
      add(Path.of("/System/Library/Fonts/AppleSDGothicNeo.ttc"))
    }
  }
}
