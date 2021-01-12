package com.btcontract.wallet.helper

import com.btcontract.wallet.ln.crypto.Tools.runAnd
import androidx.loader.content.AsyncTaskLoader
import android.content.Context
import android.database.Cursor
import scodec.bits.ByteVector
import scala.util.Try


case class RichCursor(c: Cursor) extends Iterable[RichCursor] { me =>
  def set[T](trans: RichCursor => T): Set[T] = try map(trans).toSet finally c.close
  def iterable[T](trans: RichCursor => T): Iterable[T] = try map(trans) finally c.close
  def headTry[T](fun: RichCursor => T): Try[T] = try Try(fun apply head) finally c.close
  def string(stringKey: String): String = c.getString(c getColumnIndex stringKey)
  def long(longKey: String): Long = c.getLong(c getColumnIndex longKey)
  def int(intKey: String): Int = c.getInt(c getColumnIndex intKey)

  def byteVec(byteKey: String): ByteVector = {
    val underlying = c.getBlob(c getColumnIndex byteKey)
    ByteVector.view(underlying)
  }

  def iterator: Iterator[RichCursor] = new Iterator[RichCursor] {
    def hasNext: Boolean = c.getPosition < c.getCount - 1
    def next: RichCursor = runAnd(me)(c.moveToNext)
  }
}

// Loading data with side effect, can be automatically notified to update
abstract class ReactLoader[T](ct: Context) extends AsyncTaskLoader[Cursor](ct) {

  def loadInBackground: Cursor = {
    val currentCursor: Cursor = getCursor
    consume(RichCursor(currentCursor) iterable createItem)
    currentCursor
  }

  val consume: Iterable[T] => Unit
  def createItem(wrap: RichCursor): T
  def getCursor: Cursor
}