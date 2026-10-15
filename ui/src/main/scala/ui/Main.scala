package ui

import com.raquo.laminar.api.L.*
import org.scalajs.dom

object Main {
  def main(args: Array[String]): Unit = {
    val _ = renderOnDomContentLoaded(dom.document.getElementById("app"), SettingsPage().view)
  }
}
