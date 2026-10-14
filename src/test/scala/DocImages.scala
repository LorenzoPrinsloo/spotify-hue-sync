import domain.engine.{Order, Palette, PaletteType, RGB}
import java.awt.Color
import java.nio.file.{Files, Path, Paths}
import scala.util.Random

/** Regenerates the images in docs/images from the real colour engine and pulse code. Run with `sbt "Test/runMain DocImages"`. */
object DocImages {

  private val out: Path = Paths.get("docs", "images")
  private val base: RGB = RGB.safe(220, 70, 40).get
  private val bpm = 20.0
  private val beatMs = 60000.0 / bpm
  private val zoneId = "00000000-0000-0000-0000-000000000000"

  def main(args: Array[String]): Unit = {
    Files.createDirectories(out)
    write("palette-analogous.svg", paletteImage("Analogous", PaletteType.Analogous, List("-30°", "base", "+30°")))
    write("palette-complementary.svg", paletteImage("Complementary", PaletteType.Complementary, List("base", "complement")))
    write("palette-analogous-complementary.svg", analogousComplementaryImage)
    write("light-mapping.svg", lightMappingImage)
    write("pulse-envelope.svg", envelopeImage)
    write("pulse-wave.svg", waveImage)
    write("pulse-timeline.svg", timelineImage)
  }

  private def write(name: String, svg: String): Unit = {
    Files.writeString(out.resolve(name), svg)
    println(s"wrote ${out.resolve(name)}")
  }

  /** A Random whose colour draws return the fixed base colour, so the images use Palette.random itself. */
  private def fixedBase: Random = new Random(new java.util.Random {
    private val channels = Iterator.continually(List(base.r.value, base.g.value, base.b.value)).flatten
    override def nextInt(bound: Int): Int = channels.next()
  })

  private def palette(mode: PaletteType): Palette = Palette.random(mode)(using fixedBase).get

  private def hex(c: RGB): String = f"#${c.r.value}%02x${c.g.value}%02x${c.b.value}%02x"
  private def hsb(c: RGB): Array[Float] = Color.RGBtoHSB(c.r.value, c.g.value, c.b.value, null)
  private def fmt(d: Double): String = String.format(java.util.Locale.ROOT, "%.2f", d)

  private def svg(width: Int, height: Int)(body: String): String =
    s"""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" font-family="Helvetica, Arial, sans-serif">
       |<rect width="$width" height="$height" fill="#ffffff"/>
       |$body
       |</svg>
       |""".stripMargin

  private def text(x: Double, y: Double, s: String, size: Int = 13, anchor: String = "start", weight: String = "normal", fill: String = "#333"): String =
    s"""<text x="${fmt(x)}" y="${fmt(y)}" font-size="$size" text-anchor="$anchor" font-weight="$weight" fill="$fill">$s</text>"""

  private def polar(cx: Double, cy: Double, r: Double, turns: Double): (Double, Double) = {
    val a = turns * 2 * math.Pi - math.Pi / 2
    (cx + r * math.cos(a), cy + r * math.sin(a))
  }

  /** A hue ring with a marker for each colour, placed at its hue. */
  private def wheel(cx: Double, cy: Double, colours: List[(RGB, String)]): String = {
    val outer = 120.0
    val inner = 80.0
    val ring = (0 until 360).map { deg =>
      val (x1, y1) = polar(cx, cy, outer, deg / 360.0)
      val (x2, y2) = polar(cx, cy, outer, (deg + 1.2) / 360.0)
      val (x3, y3) = polar(cx, cy, inner, (deg + 1.2) / 360.0)
      val (x4, y4) = polar(cx, cy, inner, deg / 360.0)
      val c = Color.getHSBColor(deg / 360f, 1f, 1f)
      f"""<path d="M${fmt(x1)},${fmt(y1)} A$outer,$outer 0 0 1 ${fmt(x2)},${fmt(y2)} L${fmt(x3)},${fmt(y3)} A$inner,$inner 0 0 0 ${fmt(x4)},${fmt(y4)} Z" fill="rgb(${c.getRed},${c.getGreen},${c.getBlue})"/>"""
    }.mkString("\n")
    val markers = colours.map { case (c, label) =>
      val h = hsb(c)(0).toDouble
      val (mx, my) = polar(cx, cy, (outer + inner) / 2, h)
      val (lx, ly) = polar(cx, cy, outer + 22, h)
      s"""<line x1="${fmt(cx)}" y1="${fmt(cy)}" x2="${fmt(mx)}" y2="${fmt(my)}" stroke="#555" stroke-width="1.5" stroke-dasharray="4 3"/>
         |<circle cx="${fmt(mx)}" cy="${fmt(my)}" r="13" fill="${hex(c)}" stroke="#fff" stroke-width="3"/>
         |<circle cx="${fmt(mx)}" cy="${fmt(my)}" r="15" fill="none" stroke="#333" stroke-width="1"/>
         |${text(lx, ly + 4, label, 12, "middle", "bold")}""".stripMargin
    }.mkString("\n")
    s"""$ring
       |<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="4" fill="#555"/>
       |$markers""".stripMargin
  }

  private def swatches(x: Double, y: Double, colours: List[(RGB, String)], size: Double = 56): String =
    colours.zipWithIndex.map { case ((c, label), i) =>
      val sx = x + i * (size + 12)
      s"""<rect x="${fmt(sx)}" y="${fmt(y)}" width="$size" height="$size" rx="8" fill="${hex(c)}" stroke="#ccc"/>
         |${text(sx + size / 2, y + size + 16, label, 11, "middle", "bold")}
         |${text(sx + size / 2, y + size + 30, hex(c), 10, "middle", fill = "#777")}""".stripMargin
    }.mkString("\n")

  private def paletteImage(title: String, mode: PaletteType, labels: List[String]): String = {
    val colours = palette(mode).colors.toList.zip(labels)
    svg(640, 350) {
      s"""${text(20, 30, s"$title palette", 18, weight = "bold")}
         |${wheel(170, 195, colours)}
         |${swatches(340, 145, colours)}""".stripMargin
    }
  }

  private def analogousComplementaryImage: String = {
    val flat = palette(PaletteType.AnalogousComplementary(Order.Flat)).colors.toList
    val interspersed = palette(PaletteType.AnalogousComplementary(Order.Interspersed)).colors.toList
    val labels = List("-30°", "base", "+30°", "-30°", "complement", "+30°")
    val wheelColours = flat.zip(labels)
    val interspersedLabels = List("-30°", "-30°", "base", "complement", "+30°", "+30°")
    svg(820, 375) {
      s"""${text(20, 30, "Analogous-complementary palette", 18, weight = "bold")}
         |${wheel(170, 205, wheelColours)}
         |${text(350, 85, "Flat: base triad, then complement triad", 13, weight = "bold")}
         |${swatches(350, 100, flat.zip(labels), 52)}
         |${text(350, 225, "Interspersed: the two triads alternate", 13, weight = "bold")}
         |${swatches(350, 240, interspersed.zip(interspersedLabels), 52)}""".stripMargin
    }
  }

  private def lightMappingImage: String = {
    val p = palette(PaletteType.Analogous)
    val names = Map(0 -> "c1", 1 -> "c2", 2 -> "c3")
    val lights = (0 until 7).map { i =>
      val c = p.getColor(i)
      val name = names(p.colors.indexOf(c))
      val x = 60 + i * 80
      s"""<circle cx="$x" cy="95" r="26" fill="${hex(c)}" stroke="#333"/>
         |${text(x, 145, s"light $i", 12, "middle", "bold")}
         |${text(x, 162, name, 12, "middle", fill = "#777")}""".stripMargin
    }.mkString("\n")
    svg(600, 185) {
      s"""${text(20, 30, "How an analogous palette [c1, c2, c3] is spread over seven lights", 15, weight = "bold")}
         |$lights""".stripMargin
    }
  }

  private case class Series(label: String, colour: String, points: Seq[(Double, Double)], width: Double = 2.5)
  private case class Band(from: Double, to: Double, label: String, fill: String)

  /** A simple line chart; x is in seconds, y in [0, yMax]. */
  private def lineChart(title: String, width: Int, height: Int, xMax: Double, yMax: Double, yLabel: String,
                        series: Seq[Series], bands: Seq[Band] = Nil, yTicks: Seq[Double], xTicks: Seq[Double]): String = {
    val left = 70.0
    val right = 30.0
    val top = 50.0
    val bottom = 60.0
    val w = width - left - right
    val h = height - top - bottom
    def px(x: Double) = left + x / xMax * w
    def py(y: Double) = top + h - y / yMax * h
    val bandSvg = bands.map { b =>
      s"""<rect x="${fmt(px(b.from))}" y="${fmt(top)}" width="${fmt(px(b.to) - px(b.from))}" height="${fmt(h)}" fill="${b.fill}"/>
         |${text((px(b.from) + px(b.to)) / 2, top + 16, b.label, 11, "middle", "bold", "#555")}""".stripMargin
    }.mkString("\n")
    val grid = yTicks.map { t =>
      s"""<line x1="${fmt(left)}" y1="${fmt(py(t))}" x2="${fmt(left + w)}" y2="${fmt(py(t))}" stroke="#e5e5e5"/>
         |${text(left - 8, py(t) + 4, if (t == t.floor) t.toInt.toString else fmt(t), 11, "end", fill = "#666")}""".stripMargin
    }.mkString("\n")
    val xAxis = xTicks.map { t =>
      s"""<line x1="${fmt(px(t))}" y1="${fmt(top + h)}" x2="${fmt(px(t))}" y2="${fmt(top + h + 5)}" stroke="#999"/>
         |${text(px(t), top + h + 20, s"${if (t == t.floor) t.toInt.toString else fmt(t)}s", 11, "middle", fill = "#666")}""".stripMargin
    }.mkString("\n")
    val lines = series.map { s =>
      val d = s.points.map { case (x, y) => s"${fmt(px(x))},${fmt(py(y))}" }.mkString(" ")
      s"""<polyline points="$d" fill="none" stroke="${s.colour}" stroke-width="${s.width}" stroke-linejoin="round"/>"""
    }.mkString("\n")
    val legend = series.zipWithIndex.map { case (s, i) =>
      val lx = left + i * ((w) / series.size.max(1))
      s"""<line x1="${fmt(lx)}" y1="${fmt(height - 18.0)}" x2="${fmt(lx + 22)}" y2="${fmt(height - 18.0)}" stroke="${s.colour}" stroke-width="3"/>
         |${text(lx + 28, height - 14.0, s.label, 11)}""".stripMargin
    }.mkString("\n")
    svg(width, height) {
      s"""${text(20, 28, title, 16, weight = "bold")}
         |$bandSvg
         |$grid
         |<line x1="${fmt(left)}" y1="${fmt(top + h)}" x2="${fmt(left + w)}" y2="${fmt(top + h)}" stroke="#999"/>
         |<line x1="${fmt(left)}" y1="${fmt(top)}" x2="${fmt(left)}" y2="${fmt(top + h)}" stroke="#999"/>
         |$xAxis
         |${text(18, top + h / 2, yLabel, 11, "middle", fill = "#666").replace("<text ", s"""<text transform="rotate(-90 18 ${fmt(top + h / 2)})" """)}
         |$lines
         |$legend""".stripMargin
    }
  }

  private val grey: RGB = RGB.safe(90, 90, 90).get

  /** The brightness multiplier of one light at a moment, read back from the real pulseFrame output. */
  private def multiplier(light: Int, ms: Long): Double =
    SyncEngine.pulseFrame(zoneId, bpm, ms, Palette(Vector(grey))).updates(light).color.r.value / 90.0

  private def samples(seconds: Double, stepMs: Int = 10): Seq[Long] = (0L to (seconds * 1000).toLong by stepMs.toLong)

  private def envelopeImage: String = {
    val beat = beatMs / 1000
    val pts = samples(beat).map(ms => (ms / 1000.0, multiplier(0, ms)))
    lineChart(
      title = s"One pulse (one beat = ${beat.toInt}s at ${bpm.toInt} BPM)",
      width = 720, height = 340, xMax = beat, yMax = 3.2,
      yLabel = "brightness multiplier",
      series = Seq(Series("light 0: 0.15 at rest, up to 2.65 at the peak", "#e05a2b", pts)),
      bands = Seq(
        Band(0, 0.2 * beat, "attack", "#fdeee7"),
        Band(0.2 * beat, 0.3 * beat, "sustain", "#fbd9cb"),
        Band(0.3 * beat, beat, "decay", "#f6f6f6")
      ),
      yTicks = Seq(0, 0.5, 1, 1.5, 2, 2.5),
      xTicks = Seq(0, 0.6, 0.9, 1.5, 2.25, 3.0)
    )
  }

  private def waveImage: String = {
    val seconds = 2 * beatMs / 1000
    val p = palette(PaletteType.Analogous)
    val left = 80.0
    val top = 50.0
    val rowH = 46.0
    val w = 720.0
    val yMax = 2.8
    def px(x: Double) = left + x / seconds * w
    val rows = (0 until 7).map { i =>
      val baseline = top + (i + 1) * rowH
      val pts = samples(seconds).map(ms => s"${fmt(px(ms / 1000.0))},${fmt(baseline - multiplier(i, ms) / yMax * (rowH - 6))}")
      val area = (s"${fmt(px(0))},${fmt(baseline)}" +: pts :+ s"${fmt(px(seconds))},${fmt(baseline)}").mkString(" ")
      val colour = hex(p.getColor(i))
      s"""<line x1="${fmt(left)}" y1="${fmt(baseline)}" x2="${fmt(left + w)}" y2="${fmt(baseline)}" stroke="#ddd"/>
         |<polygon points="$area" fill="$colour" fill-opacity="0.25"/>
         |<polyline points="${pts.mkString(" ")}" fill="none" stroke="$colour" stroke-width="2.2"/>
         |${text(left - 10, baseline - 6, s"light $i", 11, "end", fill = "#555")}""".stripMargin
    }.mkString("\n")
    val bottom = top + 7 * rowH
    val ticks = (0 to seconds.toInt).map { t =>
      s"""<line x1="${fmt(px(t))}" y1="${fmt(top)}" x2="${fmt(px(t))}" y2="${fmt(bottom)}" stroke="#f0f0f0"/>
         |${text(px(t), bottom + 18, s"${t}s", 11, "middle", fill = "#666")}""".stripMargin
    }.mkString("\n")
    svg(830, (bottom + 60).toInt) {
      s"""${text(20, 28, "The wave: each light peaks 0.15 of a beat (0.45s) after the one before", 16, weight = "bold")}
         |$ticks
         |$rows
         |${text(left, bottom + 44, "Each row is one light's brightness over two beats. The peaks step down and to the right: that diagonal is the wave moving across the zone.", 11, fill = "#666")}""".stripMargin
    }
  }

  private def timelineImage: String = {
    val seconds = 2 * beatMs / 1000
    val p = palette(PaletteType.Analogous)
    val left = 80.0
    val top = 50.0
    val rowH = 30.0
    val stepMs = 25
    val cols = (seconds * 1000 / stepMs).toInt
    val colW = 720.0 / cols
    val cells = (0 until cols).flatMap { col =>
      val frame = SyncEngine.pulseFrame(zoneId, bpm, (col * stepMs).toLong, p)
      frame.updates.map { u =>
        s"""<rect x="${fmt(left + col * colW)}" y="${fmt(top + u.id * rowH)}" width="${fmt(colW + 0.4)}" height="${fmt(rowH - 3)}" fill="${hex(u.color)}"/>"""
      }
    }.mkString("\n")
    val labels = (0 until 7).map(i => text(left - 10, top + i * rowH + 19, s"light $i", 11, "end", fill = "#555")).mkString("\n")
    val xTicks = (0 to seconds.toInt).map { s =>
      val x = left + s * 1000.0 / stepMs * colW
      text(x, top + 7 * rowH + 16, s"${s}s", 11, "middle", fill = "#666")
    }.mkString("\n")
    svg(830, (top + 7 * rowH + 30).toInt) {
      s"""${text(20, 28, "What the lights actually show over two beats (analogous palette)", 16, weight = "bold")}
         |$cells
         |$labels
         |$xTicks""".stripMargin
    }
  }
}
