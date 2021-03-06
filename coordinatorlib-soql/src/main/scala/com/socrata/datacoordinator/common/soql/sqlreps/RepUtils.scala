package com.socrata.datacoordinator.common.soql.sqlreps

import java.lang.StringBuilder

abstract class RepUtils {
  def standardNullInsertSize = 8

  def sqlescape(s: String): String = {
    val sb = new StringBuilder
    sqlescape(sb, s)
    sb.toString
  }

  def sqlescape(sb: StringBuilder, s: String) =
    doubler('\'', sb, s)

  def csvescape(sb: StringBuilder, s: String) =
    doubler('"', sb, s)

  def doubler(q: Char, sb: StringBuilder, s: String) = {
    sb.append(q)
    var i = 0
    while(i != s.length) {
      val c = s.charAt(i)
      if(c == q) sb.append(c)
      sb.append(c)
      i += 1
    }
    sb.append(q)
  }
}
