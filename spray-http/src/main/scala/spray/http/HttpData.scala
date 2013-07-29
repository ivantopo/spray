/*
 * Copyright (C) 2011-2013 spray.io
 * Based on code copyright (C) 2010-2011 by the BlueEyes Web Framework Team (http://github.com/jdegoes/blueeyes)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.http

import java.io.{ FileInputStream, File }
import java.nio.charset.Charset
import scala.annotation.tailrec
import akka.util.ByteString

sealed abstract class HttpData {
  def isEmpty: Boolean
  def length: Long
  def copyToArray(xs: Array[Byte], start: Int = 0, len: Int = length.toInt): Unit
  def toByteArray: Array[Byte]
  def +:(other: HttpData): HttpData
  def asString: String = asString(UTF8)
  def asString(charset: HttpCharset): String = asString(charset.nioCharset)
  def asString(charset: java.nio.charset.Charset): String = new String(toByteArray, charset)
}

object HttpData {
  def apply(string: String): HttpData =
    apply(string, HttpCharsets.`UTF-8`)
  def apply(string: String, charset: HttpCharset): HttpData =
    if (string.isEmpty) Empty else new Bytes(ByteString(string getBytes charset.nioCharset))
  def apply(bytes: Array[Byte]): HttpData =
    if (bytes.isEmpty) Empty else new Bytes(ByteString(bytes))
  def apply(bytes: ByteString): HttpData =
    if (bytes.isEmpty) Empty else new Bytes(bytes)

  /**
   * Creates an HttpData.FileBytes instance if the given file exists, is readable, non-empty
   * and the given `len` parameter is non-zero. Otherwise the method returns HttpData.Empty.
   * A negative `len` value signifies that the respective number of bytes at the end of the
   * file is to be ommited, i.e., a value of -10 will select all bytes starting at `offset`
   * except for the last 10.
   * If `len` is greater or equal to "file length - offset" all bytes in the file starting at
   * `offset` are selected.
   */
  def apply(file: File, offset: Long = 0, len: Long = Long.MaxValue): HttpData = {
    require(offset >= 0, "offset must be >= 0")
    val fLen = file.length
    if (file.canRead && fLen > 0 && offset < fLen && (len > 0 || (len < 0) && (len > offset - fLen)))
      new FileBytes(file, offset, len)
    else
      Empty
  }

  case object Empty extends HttpData {
    def isEmpty = true
    def length = 0L
    def copyToArray(xs: Array[Byte], start: Int, len: Int) = ()
    val toByteArray = Array.empty[Byte]
    def +:(other: HttpData) = other
    override def asString(charset: Charset) = ""
  }

  sealed abstract class NonEmpty extends HttpData {
    def isEmpty = false
    def +:(other: HttpData): NonEmpty =
      other match {
        case Empty                                 ⇒ this
        case x: CompactNonEmpty                    ⇒ Compound(x, this)
        case Compound(head, tail: CompactNonEmpty) ⇒ Compound(head, Compound(tail, this))
        case x: Compound ⇒
          val Compound(revHead, revTail: Compound) = x.reverse
          @tailrec def rec(current: Compound = revTail, result: Compound = Compound(revHead, this)): Compound = {
            val next = Compound(current.head, result)
            current.tail match {
              case x: CompactNonEmpty ⇒ Compound(x, next)
              case x: Compound        ⇒ rec(x, next)
            }
          }
          rec()
      }
    def toByteArray = {
      val array = Array.ofDim[Byte](length.toInt)
      copyToArray(array)
      array
    }
  }

  sealed abstract class CompactNonEmpty extends NonEmpty { _: Product ⇒
    override def toString = s"$productPrefix(<$length bytes>)"
  }

  case class Bytes private[HttpData] (bytes: ByteString) extends CompactNonEmpty {
    def length = bytes.length
    def copyToArray(xs: Array[Byte], start: Int, len: Int) = bytes.copyToArray(xs, start, len)
  }

  case class FileBytes private[HttpData] (file: File, offset: Long = 0, len: Long = 0) extends CompactNonEmpty {
    def length = file.length
    def copyToArray(xs: Array[Byte], start: Int, l: Int): Unit = {
      val end = math.min(
        if (len > 0) math.min(length, offset + len) else length + len,
        offset + l).toInt
      if (start < end) {
        val stream = new FileInputStream(file)
        @tailrec def load(start: Int = offset.toInt): Unit =
          if (start < end)
            stream.read(xs, start, end - start) match {
              case -1    ⇒ // file length changed since we calculated `end` index
              case count ⇒ load(start + count)
            }
        stream.close()
      }
    }
  }

  case class Compound private[HttpData] (head: CompactNonEmpty, tail: NonEmpty) extends NonEmpty {
    def length =
      tail match {
        case t: CompactNonEmpty ⇒ head.length + t.length
        case t: Compound ⇒
          @tailrec def rec(current: Compound = t, len: Long = head.length): Long = {
            val next = len + current.head.length
            current.tail match {
              case x: CompactNonEmpty ⇒ next + x.length
              case x: Compound        ⇒ rec(x, next)
            }
          }
          rec()
      }
    def reverse: Compound =
      tail match {
        case t: CompactNonEmpty ⇒ Compound(t, head)
        case t: Compound ⇒
          @tailrec def rec(current: Compound = t, result: NonEmpty = head): Compound = {
            val next = Compound(current.head, result)
            current.tail match {
              case x: CompactNonEmpty ⇒ Compound(x, next)
              case x: Compound        ⇒ rec(x, next)
            }
          }
          rec()
      }
    def copyToArray(xs: Array[Byte], start: Int, len: Int) = {
      @tailrec def rec(current: Compound = this, offset: Int = 0): Unit = {
        current.head.copyToArray(xs, offset)
        val nextOffset = offset + current.head.length.toInt
        current.tail match {
          case x: CompactNonEmpty ⇒ x.copyToArray(xs, nextOffset)
          case x: Compound        ⇒ rec(x, nextOffset)
        }
      }
      rec()
    }
    override def toString = head.toString + " +: " + tail
  }
}