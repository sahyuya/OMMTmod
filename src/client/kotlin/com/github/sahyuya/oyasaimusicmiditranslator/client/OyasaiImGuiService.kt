package com.github.sahyuya.oyasaimusicmiditranslator.client

import cn.enaium.fabric.imgui.DefaultImGui
import cn.enaium.fabric.imgui.FabricImGui
import imgui.ImFontConfig
import imgui.ImFontGlyphRangesBuilder
import imgui.ImGuiIO
import imgui.flag.ImGuiConfigFlags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/**
 * OMMT-owned configuration for the Fabric ImGui bridge.
 *
 * Base layer is the bundled Noto Sans JP (guaranteed Japanese + transport symbols),
 * then system CJK fonts are merged for simplified-Chinese-only glyphs that Japanese
 * fonts lack (Yu Gothic/Meiryo cover JIS, not GB). Falls back to the ImGui default
 * font only when nothing else loads.
 *
 * Fonts rasterize at [BASE_SIZE_PX] * uiScale so scaled text stays crisp instead of
 * bilinear-upscaling an 18px atlas ([rebuildAtlas]). GPU texture invalidation covers
 * the GL backend directly; the Blaze3D backend is handled reflectively with an
 * upscale fallback.
 */
class OyasaiImGuiService : DefaultImGui("ommt-imgui-layout") {
  private val logger = LoggerFactory.getLogger("OMMT/ImGuiFont")

  // Transport + punctuation symbols used by editor labels. Must stay in sync with
  // button labels (▶|◀/⏹). U+23F9 comes from the Segoe-UI-Symbol/DejaVu merge below;
  // U+23F8/U+23EE are intentionally unused. No VS16 (U+FE0F): ImGui has no
  // variation-selector handling and the selector itself would render as tofu.
  private val symbolText =
      "★☆♪♫→←↑↓○◎●—～…‥、。〈〉《》「」『』【】［］（）％＆￥°×÷±§©®™▶◀■‖｜⏹"

  override fun configure(io: ImGuiIO) {
    loadFontsInto(io, BASE_SIZE_PX)
    val layout = FabricLoader.getInstance().configDir.resolve("ommt-imgui-layout.ini")
    if (!Files.exists(layout)) runCatching {
      Files.createDirectories(layout.parent)
      OyasaiImGuiService::class.java.getResourceAsStream("/assets/oyasaimusicmiditranslator/default-imgui-layout.ini")?.use { input -> Files.copy(input, layout) }
    }
    io.setIniFilename(layout.toString())
    io.setConfigFlags(ImGuiConfigFlags.DockingEnable)
  }

  private fun loadFontsInto(io: ImGuiIO, sizePx: Float): Boolean {
    val atlas = io.fonts
    val baseRanges =
        ImFontGlyphRangesBuilder().apply {
          addRanges(atlas.glyphRangesDefault)
          addRanges(atlas.glyphRangesJapanese)
          addRanges(atlas.glyphRangesChineseFull)
          addText(symbolText)
        }.buildRanges()
    // Oversample keeps small CJK strokes crisp on HiDPI.
    fun oversampled() = ImFontConfig().apply { setOversampleH(2); setOversampleV(2) }
    var loaded = false
    // 1. Bundled Noto Sans JP: guaranteed Japanese + symbols on every machine.
    runCatching {
      OyasaiImGuiService::class.java
          .getResourceAsStream("/assets/oyasaimusicmiditranslator/fonts/NotoSansJP-Regular.ttf")
          ?.use { it.readBytes() }
          ?.takeIf { it.isNotEmpty() }
          ?.let { bytes ->
            val font = atlas.addFontFromMemoryTTF(bytes, sizePx, oversampled(), baseRanges)
            if (font != null && font.isLoaded()) {
              logger.info("Bundled NotoSansJP loaded ({} bytes, {}px)", bytes.size, sizePx)
              loaded = true
            }
          }
    }.onFailure { logger.warn("Bundled NotoSansJP failed, trying system fonts", it) }
    // 2. System CJK when the bundle is missing.
    if (!loaded) {
      val fallback = japaneseFontCandidates().firstOrNull(Files::isRegularFile)
      if (fallback != null) {
        runCatching {
          val font = atlas.addFontFromFileTTF(fallback.toString(), sizePx, oversampled(), baseRanges)
          if (font != null && font.isLoaded()) {
            logger.info("System CJK font loaded: {} ({}px)", fallback, sizePx)
            loaded = true
          }
        }.onFailure { logger.warn("System CJK font failed: {}", fallback, it) }
      }
    }
    if (loaded) {
      // Simplified-Chinese-only glyphs missing from Japanese fonts.
      val mergeConfig = ImFontConfig().apply { setMergeMode(true); setOversampleH(2); setOversampleV(2) }
      for (candidate in simplifiedChineseCandidates()) {
        if (!Files.isRegularFile(candidate)) continue
        val merged =
            runCatching {
              val font =
                  atlas.addFontFromFileTTF(
                      candidate.toString(),
                      sizePx,
                      mergeConfig,
                      scRanges(atlas),
                  )
              font != null && font.isLoaded()
            }.getOrDefault(false)
        if (merged) {
          logger.info("Merged simplified-Chinese fallback: {}", candidate)
          break
        }
      }
      // Misc-Technical symbols (U+23F9 ⏹ etc.) absent from all CJK fonts above.
      val symbolRanges =
          ImFontGlyphRangesBuilder().apply {
            addText("⏹⏸⏺⏏▶◀■‖")
          }.buildRanges()
      for (candidate in symbolFontCandidates()) {
        if (!Files.isRegularFile(candidate)) continue
        val merged =
            runCatching {
              val font =
                  atlas.addFontFromFileTTF(candidate.toString(), sizePx, mergeConfig, symbolRanges)
              font != null && font.isLoaded()
            }.getOrDefault(false)
        if (merged) {
          logger.info("Merged symbol fallback: {}", candidate)
          break
        }
      }
    } else {
      logger.warn("No CJK font available; Japanese text will render as tofu")
      atlas.addFontDefault()
    }
    return atlas.build()
  }

  private fun scRanges(atlas: imgui.ImFontAtlas): ShortArray =
      ImFontGlyphRangesBuilder().apply {
        addRanges(atlas.glyphRangesChineseSimplifiedCommon)
        addRanges(atlas.glyphRangesJapanese)
      }.buildRanges()

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

  private fun simplifiedChineseCandidates(): List<Path> {
    val windows = System.getenv("WINDIR")?.let(Path::of)
    return buildList {
      if (windows != null) {
        // Microsoft YaHei UI / SimHei cover GB simplified glyphs missing from JIS fonts.
        add(windows.resolve("Fonts/msyh.ttc"))
        add(windows.resolve("Fonts/msyhbd.ttc"))
        add(windows.resolve("Fonts/simhei.ttf"))
        add(windows.resolve("Fonts/simsun.ttc"))
      }
      add(Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
      add(Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"))
    }
  }

  /**
   * Symbol fonts for Misc-Technical glyphs absent from CJK fonts (notably U+23F9 ⏹
   * used by the transport toggle). Merged, never primary.
   */
  private fun symbolFontCandidates(): List<Path> {
    val windows = System.getenv("WINDIR")?.let(Path::of)
    return buildList {
      if (windows != null) {
        // Segoe UI Symbol is inbox since Windows 7 and covers U+23F8..U+23FA.
        add(windows.resolve("Fonts/seguisym.ttf"))
      }
      add(Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
      add(Path.of("/System/Library/Fonts/Apple Symbols.ttf"))
    }
  }

  companion object {
    /** Raster size at 100% UI scale. Larger than the old 18px: Noto metrics run smaller than Yu Gothic. */
    const val BASE_SIZE_PX = 20f

    @Volatile private var pendingSizePx: Float? = null
    @Volatile private var failedSizePx: Float? = null

    /** Request an atlas rebuild at the given raster size. Executed later via [pollRebuild]. */
    fun requestRebuild(sizePx: Float) {
      if (failedSizePx?.let { abs(it - sizePx) < .001f } == true) return
      pendingSizePx = sizePx
    }

    /**
     * Runs a pending rebuild outside the ImGui frame lock. Returns the new raster size
     * on success, null when nothing was pending, the atlas is locked, or it failed
     * (caller keeps the upscale fallback in those cases).
     */
    fun pollRebuild(io: ImGuiIO): Float? {
      val sizePx = pendingSizePx ?: return null
      val atlas = io.fonts
      if (atlas.getLocked()) return null
      val service = FabricImGui.IMGUI as? OyasaiImGuiService ?: run {
        pendingSizePx = null
        return null
      }
      val ok = runCatching {
        atlas.clear()
        service.loadFontsInto(io, sizePx) && service.invalidateTextures()
      }.getOrDefault(false)
      // A failed rebuild must not retry every tick (upscale fallback stays).
      pendingSizePx = null
      if (!ok) {
        failedSizePx = sizePx
        return null
      }
      failedSizePx = null
      return sizePx
    }

    /**
     * Forces GPU re-upload of the font atlas on next frame. GL backend has a public
     * hook; the Blaze3D backend is handled reflectively (pinned bridge version).
     * Returns false when invalidation was impossible (caller keeps upscale fallback).
     */
    private fun OyasaiImGuiService.invalidateTextures(): Boolean {
      val impl = FabricImGui.IMGUI as? DefaultImGui ?: return false
      impl.imGuiImplGl3?.let {
        runCatching { it.destroyFontsTexture() }.onFailure { _ -> return false }
        return true
      }
      impl.imGuiImplBlaze3D?.let { blaze ->
        return runCatching {
          blaze.javaClass.getDeclaredMethod("disposeFontResources").apply { isAccessible = true }.invoke(blaze)
          blaze.javaClass.getDeclaredField("fontTexture").apply { isAccessible = true }.set(blaze, null)
          true
        }.getOrDefault(false)
      }
      return false
    }
  }
}
