package boopickle

import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.language.higherKinds
import scala.reflect.ClassTag

trait Pickler[A] {
  def pickle(obj: A)(implicit state: PickleState): Unit
  def unpickle(implicit state: UnpickleState): A

  def xmap[B](ab: A => B)(ba: B => A): Pickler[B] = {
    val self = this
    new Pickler[B] {
      override def unpickle(implicit state: UnpickleState): B = {
        ab(self.unpickle(state))
      }
      override def pickle(obj: B)(implicit state: PickleState): Unit = {
        self.pickle(ba(obj))
      }
    }
  }
}

/**
  * A Pickler that always returns a constant value.
  *
  * Stores nothing in the pickled output.
  */
final case class ConstPickler[A](a: A) extends Pickler[A] {
  @inline override def pickle(x: A)(implicit s: PickleState) = ()
  @inline override def unpickle(implicit s: UnpickleState) = a
}

trait PicklerHelper {
  protected type P[A] = Pickler[A]

  /**
    * Helper function to write pickled types
    */
  protected def write[A](value: A)(implicit state: PickleState, p: P[A]): Unit = p.pickle(value)(state)

  /**
    * Helper function to unpickle a type
    */
  protected def read[A](implicit state: UnpickleState, u: P[A]): A = u.unpickle
}

object BasicPicklers extends PicklerHelper {

  import Constants._

  val UnitPickler = ConstPickler(())

  object BooleanPickler extends P[Boolean] {
    @inline override def pickle(value: Boolean)
      (implicit state: PickleState): Unit = state.enc.writeByte(if (value) 1 else 0)
    @inline override def unpickle(implicit state: UnpickleState): Boolean = {
      state.dec.readByte match {
        case 1 => true
        case 0 => false
        case x => throw new IllegalArgumentException(s"Invalid value $x for Boolean")
      }
    }
  }

  object BytePickler extends P[Byte] {
    @inline override def pickle(value: Byte)(implicit state: PickleState): Unit = state.enc.writeByte(value)
    @inline override def unpickle(implicit state: UnpickleState): Byte = state.dec.readByte
  }

  object ShortPickler extends P[Short] {
    @inline override def pickle(value: Short)(implicit state: PickleState): Unit = state.enc.writeShort(value)
    @inline override def unpickle(implicit state: UnpickleState): Short = state.dec.readShort
  }

  object CharPickler extends P[Char] {
    @inline override def pickle(value: Char)(implicit state: PickleState): Unit = state.enc.writeChar(value)
    @inline override def unpickle(implicit state: UnpickleState): Char = state.dec.readChar
  }

  object IntPickler extends P[Int] {
    @inline override def pickle(value: Int)(implicit state: PickleState): Unit = state.enc.writeInt(value)
    @inline override def unpickle(implicit state: UnpickleState): Int = state.dec.readInt
  }

  object LongPickler extends P[Long] {
    @inline override def pickle(value: Long)(implicit state: PickleState): Unit = state.enc.writeLong(value)
    @inline override def unpickle(implicit state: UnpickleState): Long = state.dec.readLong
  }

  object FloatPickler extends P[Float] {
    @inline override def pickle(value: Float)(implicit state: PickleState): Unit = state.enc.writeFloat(value)
    @inline override def unpickle(implicit state: UnpickleState): Float = state.dec.readFloat
  }

  object DoublePickler extends P[Double] {
    @inline override def pickle(value: Double)(implicit state: PickleState): Unit = state.enc.writeDouble(value)
    @inline override def unpickle(implicit state: UnpickleState): Double = state.dec.readDouble
  }

  object ByteBufferPickler extends P[ByteBuffer] {
    @inline override def pickle(bb: ByteBuffer)(implicit state: PickleState): Unit = state.enc.writeByteBuffer(bb)
    @inline override def unpickle(implicit state: UnpickleState): ByteBuffer = state.dec.readByteBuffer
  }

  object BigIntPickler extends P[BigInt] {
    implicit def bp = BytePickler

    @inline override def pickle(value: BigInt)(implicit state: PickleState): Unit = {
      ArrayPickler.pickle(value.toByteArray)
    }
    @inline override def unpickle(implicit state: UnpickleState): BigInt = {
      BigInt(ArrayPickler.unpickle)
    }
  }

  object BigDecimalPickler extends P[BigDecimal] {
    implicit def bp = BytePickler

    @inline override def pickle(value: BigDecimal)(implicit state: PickleState): Unit = {
      state.enc.writeInt(value.scale)
      ArrayPickler.pickle(value.underlying().unscaledValue.toByteArray)
    }
    @inline override def unpickle(implicit state: UnpickleState): BigDecimal = {
      val scale = state.dec.readInt
      val arr = ArrayPickler.unpickle
      BigDecimal(BigInt(arr), scale)
    }
  }

  object StringPickler extends P[String] {
    override def pickle(s: String)(implicit state: PickleState): Unit = {
      state.identityRefFor(s) match {
        case Some(idx) =>
          state.enc.writeInt(-idx)
        case None =>
          if (s.isEmpty) {
            state.enc.writeInt(0)
          } else {
            state.enc.writeString(s)
            state.addIdentityRef(s)
          }
      }
    }

    override def unpickle(implicit state: UnpickleState): String = {
      val len = state.dec.readInt
      if (len < 0) {
        state.identityFor[String](-len)
      } else if (len == 0) {
        ""
      } else {
        val s = state.dec.readString(len)
        state.addIdentityRef(s)
        s
      }
    }
  }

  object UUIDPickler extends P[UUID] {
    override def pickle(s: UUID)(implicit state: PickleState): Unit = {
      if (s == null) {
        state.enc.writeRawLong(0)
        state.enc.writeRawLong(0)
        state.enc.writeByte(0)
      } else {
        val msb = s.getMostSignificantBits
        val lsb = s.getLeastSignificantBits
        state.enc.writeRawLong(msb)
        state.enc.writeRawLong(lsb)
        // special encoding for UUID zero, to differentiate from null
        if (msb == 0 && lsb == 0)
          state.enc.writeByte(1)
      }
    }

    @inline override def unpickle(implicit state: UnpickleState): UUID = {
      val msb = state.dec.readRawLong
      val lsb = state.dec.readRawLong

      if (msb == 0 && lsb == 0) {
        val actualUuidByte = state.dec.readByte
        if (actualUuidByte == 0) null else new UUID(0, 0)
      } else
        new UUID(msb, lsb)
    }
  }

  object DurationPickler extends P[Duration] {
    override def pickle(value: Duration)(implicit state: PickleState): Unit = {
      // take care of special Durations
      value match {
        case null =>
          state.enc.writeLongCode(Left(NullObject.toByte))
        case Duration.Inf =>
          state.enc.writeLongCode(Left(DurationInf))
        case Duration.MinusInf =>
          state.enc.writeLongCode(Left(DurationMinusInf))
        case x if x eq Duration.Undefined =>
          state.enc.writeLongCode(Left(DurationUndefined))
        case x =>
          state.enc.writeLongCode(Right(x.toNanos))
      }
    }

    @inline override def unpickle(implicit state: UnpickleState): Duration = {
      state.dec.readLongCode match {
        case Left(NullObject) =>
          null
        case Left(DurationInf) =>
          Duration.Inf
        case Left(DurationMinusInf) =>
          Duration.MinusInf
        case Left(DurationUndefined) =>
          Duration.Undefined
        case Right(value) =>
          Duration.fromNanos(value)
        case Left(_) =>
          null
      }
    }
  }

  def FiniteDurationPickler: P[FiniteDuration] = DurationPickler.asInstanceOf[P[FiniteDuration]]

  def InfiniteDurationPickler: P[Duration.Infinite] = DurationPickler.asInstanceOf[P[Duration.Infinite]]

  def OptionPickler[T: P]: P[Option[T]] = new P[Option[T]] {
    override def pickle(obj: Option[T])(implicit state: PickleState): Unit = {
      obj match {
        case null =>
          state.enc.writeInt(NullObject)
        case Some(x) =>
          state.enc.writeInt(OptionSome.toInt)
          write[T](x)
        case None =>
          // `None` is always encoded as zero
          state.enc.writeInt(OptionNone.toInt)
      }
    }

    override def unpickle(implicit state: UnpickleState): Option[T] = {
      state.dec.readInt match {
        case NullObject =>
          null
        case OptionSome =>
          val o = Some(read[T])
          o
        case OptionNone =>
          None
        case _ =>
          throw new IllegalArgumentException("Invalid coding for Option type")
      }
    }
  }

  def SomePickler[T: P]: P[Some[T]] = OptionPickler[T].asInstanceOf[P[Some[T]]]

  def EitherPickler[T: P, S: P]: P[Either[T, S]] = new P[Either[T, S]] {
    override def pickle(obj: Either[T, S])(implicit state: PickleState): Unit = {
      obj match {
        case null =>
          state.enc.writeInt(NullObject)
        case Left(l) =>
          state.enc.writeInt(EitherLeft.toInt)
          write[T](l)
        case Right(r) =>
          state.enc.writeInt(EitherRight.toInt)
          write[S](r)
      }
    }

    override def unpickle(implicit state: UnpickleState): Either[T, S] = {
      state.dec.readInt match {
        case NullObject =>
          null
        case EitherLeft =>
          Left(read[T])
        case EitherRight =>
          Right(read[S])
        case _ =>
          throw new IllegalArgumentException("Invalid coding for Either type")
      }
    }
  }

  def LeftPickler[T: P, S: P]: P[Left[T, S]] = EitherPickler[T, S].asInstanceOf[P[Left[T, S]]]

  def RightPickler[T: P, S: P]: P[Right[T, S]] = EitherPickler[T, S].asInstanceOf[P[Right[T, S]]]

  import collection.generic.CanBuildFrom
  /**
    * This pickler works on all collections that derive from Iterable (Vector, Set, List, etc)
    *
    * @tparam T type of the values
    * @tparam V type of the collection
    * @return
    */
  def IterablePickler[T: P, V[_] <: Iterable[_]](implicit cbf: CanBuildFrom[Nothing, T, V[T]]): P[V[T]] = new P[V[T]] {
    override def pickle(iterable: V[T])(implicit state: PickleState): Unit = {
      if (iterable == null) {
        state.enc.writeInt(NullRef)
      } else {
        // encode length
        state.enc.writeInt(iterable.size)
        // encode contents
        iterable.iterator.asInstanceOf[Iterator[T]].foreach(a => write[T](a))
      }
    }

    override def unpickle(implicit state: UnpickleState): V[T] = {
      state.dec.readInt match {
        case NullRef =>
          null.asInstanceOf[V[T]]
        case 0 =>
          // empty sequence
          val res = cbf().result()
          res
        case len =>
          val b = cbf()
          var i = 0
          while (i < len) {
            b += read[T]
            i += 1
          }
          val res = b.result()
          res
      }
    }
  }

  /**
    * Specific pickler for Arrays
    *
    * @tparam T Type of values
    * @return
    */
  def ArrayPickler[T: P : ClassTag]: P[Array[T]] = new P[Array[T]] {
    override def pickle(array: Array[T])(implicit state: PickleState): Unit = {
      if (array == null)
        state.enc.writeRawInt(NullRef)
      else {
        // check if this iterable has been pickled already
        implicitly[ClassTag[T]] match {
          // handle specialization
          case ClassTag.Byte =>
            state.enc.writeByteArray(array.asInstanceOf[Array[Byte]])
          case ClassTag.Int =>
            state.enc.writeIntArray(array.asInstanceOf[Array[Int]])
          case ClassTag.Float =>
            state.enc.writeFloatArray(array.asInstanceOf[Array[Float]])
          case ClassTag.Double =>
            state.enc.writeDoubleArray(array.asInstanceOf[Array[Double]])
          case _ =>
            // encode length
            state.enc.writeRawInt(array.length)
            // encode contents
            array.foreach(a => write[T](a))
        }
      }
    }

    override def unpickle(implicit state: UnpickleState): Array[T] = {
      state.dec.readRawInt match {
        case NullRef =>
          null
        case 0 =>
          // empty Array
          Array.empty[T]
        case len =>
          val r = implicitly[ClassTag[T]] match {
            // handle specialization
            case ClassTag.Byte =>
              state.dec.readByteArray(len).asInstanceOf[Array[T]]
            case ClassTag.Int =>
              state.dec.readIntArray(len).asInstanceOf[Array[T]]
            case ClassTag.Float =>
              state.dec.readFloatArray(len).asInstanceOf[Array[T]]
            case ClassTag.Double =>
              // remove padding
              state.dec.readRawInt
              state.dec.readDoubleArray(len).asInstanceOf[Array[T]]
            case _ =>
              val a = new Array[T](len)
              var i = 0
              while (i < len) {
                a(i) = read[T]
                i += 1
              }
              a
          }
          r
      }
    }
  }

  /**
    * Maps require a specific pickler as they have two type parameters.
    *
    * @tparam T Type of keys
    * @tparam S Type of values
    * @return
    */
  def MapPickler[T: P, S: P, V[_, _] <: scala.collection.Map[_, _]]
  (implicit cbf: CanBuildFrom[Nothing, (T, S), V[T, S]]): P[V[T, S]] = new P[V[T, S]] {
    override def pickle(map: V[T, S])(implicit state: PickleState): Unit = {
      if (map == null) {
        state.enc.writeInt(NullRef)
      } else {
        // encode length
        state.enc.writeInt(map.size)
        // encode contents as a sequence
        val kPickler = implicitly[P[T]]
        val vPickler = implicitly[P[S]]
        map.asInstanceOf[scala.collection.Map[T, S]].foreach { kv =>
          kPickler.pickle(kv._1)(state)
          vPickler.pickle(kv._2)(state)
        }
      }
    }

    override def unpickle(implicit state: UnpickleState): V[T, S] = {
      state.dec.readInt match {
        case NullRef =>
          null.asInstanceOf[V[T, S]]
        case 0 =>
          // empty map
          val res = cbf().result()
          res
        case idx if idx < 0 =>
          state.identityFor[V[T, S]](-idx)
        case len =>
          val b = cbf()
          val kPickler = implicitly[P[T]]
          val vPickler = implicitly[P[S]]
          var i = 0
          while (i < len) {
            b += kPickler.unpickle(state) -> vPickler.unpickle(state)
            i += 1
          }
          val res = b.result()
          res
      }
    }
  }
}

/**
  * Manage state for a pickling "session".
  *
  * @param enc         Encoder instance to use
  * @param deduplicate Set to `true` if you want to enable deduplication
  */
final class PickleState(val enc: Encoder, deduplicate: Boolean = false) {

  /**
    * Object reference for pickled objects (use identity for equality comparison)
    *
    * Index 0 is not used
    * Index 1 = null
    * Index 2-n, references to pickled objects
    */
  private[this] var identityRefs: IdentMap = EmptyIdentMap

  @inline def identityRefFor(obj: AnyRef): Option[Int] = {
    if (obj == null)
      Some(1)
    else if (!deduplicate)
      None
    else
      identityRefs(obj)
  }

  @inline def addIdentityRef(obj: AnyRef): Unit = {
    if (deduplicate)
      identityRefs = identityRefs.updated(obj)
  }

  @inline def pickle[A](value: A)(implicit p: Pickler[A]): PickleState = {
    p.pickle(value)(this)
    this
  }

  def toByteBuffer = enc.asByteBuffer

  def toByteBuffers = enc.asByteBuffers
}

object PickleState {

  /**
    * Provides a default PickleState if none is available implicitly
    *
    * @return
    */
  implicit def Default: PickleState = new PickleState(new EncoderSize)
}

/**
  * Manage state for an unpickling "session"
  *
  * @param dec         Decoder instance to use
  * @param deduplicate Set to `true` if you want to enable deduplication
  */
final class UnpickleState(val dec: Decoder, deduplicate: Boolean = false) {
  /**
    * Object reference for pickled objects (use identity for equality comparison)
    *
    * Index 0 is not used
    * Index 1 = null
    * Index 2-n, references to pickled objects
    */
  private[this] var identityRefs: IdentList = EmptyIdentList

  @inline def identityFor[A <: AnyRef](ref: Int): A = {
    if (ref < 2)
      null.asInstanceOf[A]
    else if (!deduplicate)
      throw new Exception("Deduplication is disabled, but identityFor was called.")
    else
      identityRefs(ref - 2).asInstanceOf[A]
  }

  @inline def addIdentityRef(obj: AnyRef): Unit =
    if (deduplicate)
      identityRefs = identityRefs.updated(obj)

  @inline def unpickle[A](implicit u: Pickler[A]): A = u.unpickle(this)
}

object UnpickleState {
  /**
    * Provides a default UnpickleState if none is available implicitly
    *
    * @return
    */
  implicit def Default: ByteBuffer => UnpickleState = bytes => new UnpickleState(new DecoderSize(bytes))

  def apply(bytes: ByteBuffer) = new UnpickleState(new DecoderSize(bytes))

  def apply(decoder: Decoder, deduplicate: Boolean = false) = new UnpickleState(decoder, deduplicate)
}
