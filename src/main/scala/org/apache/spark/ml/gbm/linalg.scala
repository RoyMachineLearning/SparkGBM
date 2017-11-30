package org.apache.spark.ml.gbm

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.{specialized => spec}


trait BinVector[@spec(Byte, Short, Int) V] extends Serializable {

  def size: Int

  def apply(index: Int): V

  def slice(sorted: Array[Int]): BinVector[V]

  def totalIter: Iterator[(Int, V)]

  def activeIter: Iterator[(Int, V)]
}

object BinVector {
  def dense[@spec(Byte, Short, Int) V: Integral : ClassTag](values: Array[V]): BinVector[V] = {
    new DenseBinVector[V](values)
  }

  def sparse[@spec(Byte, Short, Int) V: Integral : ClassTag](size: Int,
                                                             indices: Array[Int],
                                                             values: Array[V]): BinVector[V] = {
    if (indices.last <= Byte.MaxValue) {
      new SparseBinVector[Byte, V](size, indices.map(_.toByte), values)
    } else if (indices.last <= Short.MaxValue) {
      new SparseBinVector[Short, V](size, indices.map(_.toShort), values)
    } else {
      new SparseBinVector[Int, V](size, indices, values)
    }
  }
}


class DenseBinVector[@spec(Byte, Short, Int) V: Integral : ClassTag](val values: Array[V]) extends BinVector[V] {

  override def size = values.length

  override def apply(index: Int) = values(index)

  override def slice(sorted: Array[Int]) =
    BinVector.dense(sorted.map(values))

  def totalIter: Iterator[(Int, V)] =
    Iterator.range(0, values.length).map(i => (i, values(i)))

  override def activeIter = {
    val intV = implicitly[Integral[V]]
    totalIter.filter(t => !intV.equiv(t._2, intV.zero))
  }
}


class SparseBinVector[@spec(Byte, Short, Int) K: Integral : ClassTag, @spec(Byte, Short, Int) V: Integral : ClassTag](val size: Int,
                                                                                                                      val indices: Array[K],
                                                                                                                      val values: Array[V]) extends BinVector[V] {

  {
    require(indices.length == values.length)
    require(size >= 0)

    val intK = implicitly[Integral[K]]
    if (indices.nonEmpty) {
      var i = 0
      while (i < indices.length - 1) {
        require(intK.lt(indices(i), indices(i + 1)))
        i += 1
      }
      require(intK.toInt(indices.last) < size)
    }
  }

  private def binarySearch = Utils.makeBinarySearch[K]

  override def apply(index: Int) = {
    val intK = implicitly[Integral[K]]
    val j = binarySearch(indices, intK.fromInt(index))
    if (j >= 0) {
      values(j)
    } else {
      val intV = implicitly[Integral[V]]
      intV.zero
    }
  }

  override def slice(sorted: Array[Int]) = {
    val intK = implicitly[Integral[K]]
    val indexBuff = mutable.ArrayBuffer[Int]()
    val valueBuff = mutable.ArrayBuffer[V]()
    var i = 0
    var j = 0
    while (i < sorted.length && j < indices.length) {
      val k = intK.toInt(indices(j))
      if (sorted(i) == k) {
        indexBuff.append(k)
        valueBuff.append(values(j))
        i += 1
        j += 1
      } else if (sorted(i) > k) {
        j += 1
      } else {
        i += 1
      }
    }

    BinVector.sparse[V](sorted.length, indexBuff.toArray, valueBuff.toArray)
  }

  def totalIter: Iterator[(Int, V)] =
    new Iterator[(Int, V)]() {
      val intK = implicitly[Integral[K]]
      val intV = implicitly[Integral[V]]

      var i = 0
      var j = 0

      override def hasNext = i < size

      override def next() = {
        val v = if (j == indices.length) {
          intV.zero
        } else {
          val k = intK.toInt(indices(j))
          if (i == k) {
            j += 1
            values(j - 1)
          } else {
            intV.zero
          }
        }
        i += 1
        (i - 1, v)
      }
    }

  override def activeIter = {
    val intK = implicitly[Integral[K]]
    val intV = implicitly[Integral[V]]
    Iterator.range(0, indices.length)
      .map(i => (intK.toInt(indices(i)), values(i)))
      .filter(t => !intV.equiv(t._2, intV.zero))
  }
}


private trait FromDouble[H] extends Serializable {
  def fromDouble(value: Double): H
}


private object DoubleFromDouble extends FromDouble[Double] {
  override def fromDouble(value: Double): Double = value
}


private object FloatFromDouble extends FromDouble[Float] {
  override def fromDouble(value: Double): Float = value.toFloat
}


private object DecimalFromDouble extends FromDouble[BigDecimal] {
  override def fromDouble(value: Double): BigDecimal = BigDecimal(value)
}


private[gbm] object FromDouble {

  implicit final val doubleFromDouble: FromDouble[Double] = DoubleFromDouble

  implicit final val floatFromDouble: FromDouble[Float] = FloatFromDouble

  implicit final val decimalFromDouble: FromDouble[BigDecimal] = DecimalFromDouble
}

