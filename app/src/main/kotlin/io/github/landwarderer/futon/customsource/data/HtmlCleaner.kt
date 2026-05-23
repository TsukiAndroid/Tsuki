package io.github.landwarderer.futon.customsource.data

  /**
   * Strips noise from raw site HTML before sending to Gemini AI.
   *
   * Removes: <script>, <style>, <head>, HTML comments, inline style= attributes,
   * <svg> elements. Collapses whitespace. Keeps only <body> content.
   * Hard-caps at [maxChars] characters, taking the middle section of the page so that
   * manga-card content (typically mid-page) is preserved over header/footer noise.
   *
   * Reduces HTML size by 70-80% while keeping the content Gemini actually needs.
   */
  object HtmlCleaner {

      private val RE_HEAD          = Regex("""<head[\s\S]*?</head>""",    RegexOption.IGNORE_CASE)
      private val RE_SCRIPT        = Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
      private val RE_STYLE_TAG     = Regex("""<style[\s\S]*?</style>""",   RegexOption.IGNORE_CASE)
      private val RE_COMMENT       = Regex("""<!--[\s\S]*?-->""")
      private val RE_SVG           = Regex("""<svg[\s\S]*?</svg>""",       RegexOption.IGNORE_CASE)
      private val RE_INLINE_STYLE  = Regex(""" style="[^"]*"""")
      private val RE_WHITESPACE    = Regex(""" {2,}""")

      /**
       * Cleans [html] and caps the result at [maxChars] characters.
       * When capping, takes the **middle** section so manga-card content is preserved.
       */
      fun cleanAndCap(html: String, maxChars: Int = 15_000): String {
          var s = html
          s = RE_HEAD.replace(s, "")
          s = RE_SCRIPT.replace(s, "")
          s = RE_STYLE_TAG.replace(s, "")
          s = RE_COMMENT.replace(s, "")
          s = RE_SVG.replace(s, "")
          s = RE_INLINE_STYLE.replace(s, "")
          s = RE_WHITESPACE.replace(s, " ")

          // Extract body content only
          val bodyStart = s.indexOf("<body", ignoreCase = true).let { if (it < 0) 0 else it }
          val bodyEnd   = s.lastIndexOf("</body>")
          s = if (bodyEnd > bodyStart) s.substring(bodyStart, bodyEnd + 7) else s.substring(bodyStart)
          s = s.trim()

          if (s.length <= maxChars) return s

          // Take middle section — manga cards live in the middle, not at header/footer edges.
          val start = (s.length - maxChars) / 2
          return s.substring(start, start + maxChars)
      }
  }
  