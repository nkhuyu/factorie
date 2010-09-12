/* Copyright (C) 2008-2010 Univ of Massachusetts Amherst, Computer Science Dept
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   This software is provided under the terms of the Eclipse Public License 1.0
   as published by http://www.opensource.org.  For further information,
   see the file `LICENSE.txt' included with this distribution. */

package cc.factorie.generative
import cc.factorie._

// Proportions is a Seq[Double] that sums to 1.0
// Discrete ~ Multinomial(Proportions)

// TODO Make a GeneratedProportions trait, which implements sampleFrom and prFrom, etc.  No.  Isn't this what Dirichlet is? -akm

// I would prefer "with Seq[Double]", but Seq implements equals/hashCode to depend on the contents,
// and no Variable should do that since we need to know about unique variables; it also makes things
// slow for large-length Proportions.
trait Proportions extends Parameter with DiscreteGenerating with IndexedSeqEqualsEq[Double] {
  /*def apply(index:Int): Double
  def length: Int
  override def size = length
  def iterator = new Iterator[Double] {
    var i = -1
    def hasNext = i + 1 < length
    def next: Double = { i = i+1; apply(i) }
  }*/
  // TODO Remove this, now that we have IndexedSeqEqualsEq
  @deprecated def asSeq: IndexedSeq[Double] = new IndexedSeq[Double] {
    def apply(i:Int) = Proportions.this.apply(i)
    def length = Proportions.this.length
  }
  def sampleInt = Maths.nextDiscrete(this.asSeq)(cc.factorie.random) // TODO Avoid the inefficiency of asSeq
  def pr(index:Int) = apply(index)
  def logpr(index:Int) = math.log(apply(index))
  def maxPrIndex: Int = { var maxIndex = 0; var i = 1; while (i < length) { if (this(i) > this(maxIndex)) maxIndex =i; i += 1 }; maxIndex }
  override def toString = asSeq.mkString(printName+"(", ",", ")")

  class DiscretePr(val index:Int, val pr:Double)
  def top(n:Int): Seq[DiscretePr] = this.asSeq.toArray.zipWithIndex.sortBy({case (p,i) => -p}).take(n).toList.map({case (p,i)=>new DiscretePr(i,p)}).filter(_.pr > 0.0)
  def klDivergence(p:Proportions): Double = Maths.klDivergence(this.asSeq, p.asSeq)
  def jsDivergence(p:Proportions): Double = Maths.jensenShannonDivergence(this.asSeq, p.asSeq)
}

// TODO try to fold this automatically into a CategoricalProportions?
trait TypedProportions[A<:DiscreteVar] extends Proportions {
  class DiscretePr(override val index:Int, override val pr:Double, val value:String) extends super.DiscretePr(index, pr)
  def top(n:Int)(implicit m:Manifest[A]): Seq[DiscretePr] = {
    val entries = this.asSeq.toArray.zipWithIndex.sortBy({case (p,i) => -p}).take(n).toList
    Domain.get[A](m.erasure) match {
      case d:CategoricalDomain[_] => entries.map({case (p,i)=>new DiscretePr(i, p, d.get(i).toString)})
      case d:Any => entries.map({case (p,i)=>new DiscretePr(i, p, "")})
    }
  }
  def topValues(n:Int)(implicit m:Manifest[A]) = top(n).toList.map(_.value)
}

trait MutableProportions extends Proportions {
  def set(p:Seq[Double])(implicit d:DiffList): Unit 
}

class DenseProportions(p:Seq[Double]) extends MutableProportions with Estimation[DenseProportions] {
  //def this(ps:Double*) = this(ps)
  def this(dim:Int) = this(Seq.fill(dim)(1.0/dim))
  private var _p = new Array[Double](p.length)
  if (p != Nil) this := p else setUniform(null)
  @inline final def length: Int = _p.size
  @inline final def apply(index:Int) = _p(index)
  def set(p:Seq[Double])(implicit d:DiffList): Unit = {
    assert(p.size == _p.size, "size mismatch: new="+p.size+", orig="+_p.size)
    val newP = p.toArray
    if (d ne null) d += ProportionsDiff(_p, newP)
    _p = newP
  }
  def :=(p:Seq[Double]) = set(p)(null)
  def setUniform(implicit d:DiffList): Unit = set(new UniformProportions(length).asSeq)
  case class ProportionsDiff(oldP:Array[Double], newP:Array[Double]) extends Diff {
    def variable = DenseProportions.this
    def undo = _p = oldP
    def redo = _p = newP
  }
}

class DiracProportions(val length:Int, val peak:Int) extends Proportions {
  @inline final def apply(index:Int) = if (index == peak) 1.0 else 0.0
}

class UniformProportions(val length:Int) extends Proportions {
  @inline final def apply(index:Int) = 1.0 / length
}

class GrowableUniformProportions(val sizeProxy:Iterable[_]) extends Proportions {
  // I used to have GrowableUniformProportions(val sizeProxy:{def size:Int}), but this results in java.lang.reflect.Method.invoke at runtime
  def length = sizeProxy.size
  @inline final def apply(index:Int) = {
    val result = 1.0 / length
    assert(length > 0, "GrowableUniformProportions domain size is zero.")
    result
  }
}

// Maintains an array of non-negative Double-valued counts which can be incremented.
//  Useful for Proportions and Dirichlets.  The counts themselves are stored in '_counts',
//  which is abstract.  The method 'length' is also abstract. 
trait IncrementableCounts extends Variable {
  protected val _counts: { def apply(i:Int):Double; def update(i:Int, x:Double):Unit; def length:Int } // Does this mean subclasses will use reflection to call these methods?
  def length: Int = _counts.length
  protected var _countsTotal: Double = 0.0
  def counts: { def apply(i:Int):Double; def update(i:Int, x:Double):Unit; def length:Int } = _counts
  def countsTotal = _countsTotal
  def count(index:Int): Double = _counts(index)
  def increment(index:Int, incr:Double)(implicit d:DiffList): Unit = {
    _counts(index) += incr; _countsTotal += incr
    if (d ne null) d += IncrementableCountsDiff(index, incr)
    assert(_counts(index) >= 0, "counts("+index+")="+_counts(index)+" after incr="+incr)
    assert(_countsTotal >= 0, "countsTotal="+_countsTotal+" after incr="+incr)
  }
  def increment(cs: Seq[Double])(implicit d:DiffList): Unit = {
    for (i <- 0 until cs.length) { 
      _counts(i) += cs(i); _countsTotal += cs(i)
      assert(_counts(i) >= 0, "counts("+i+")="+_counts(i)+" after incr="+cs(i))
      assert(_countsTotal >= 0, "countsTotal="+_countsTotal+" after incr="+cs(i))
    }
    if (d ne null) d += IncrementableCountsSeqDiff(cs)
  }
  def zero(implicit d:DiffList = null): Unit = 
    for (i <- 0 until length) if (_counts(i) > 0.0) increment(i, -_counts(i))
  def set(cs:Seq[Double], normalize:Boolean = true): Unit = {
    // TODO normalize is currently ignored.
    zero(null); increment(cs)(null)
  }
  case class IncrementableCountsDiff(index:Int, incr:Double) extends Diff {
    def variable = IncrementableCounts.this
    def undo = { _counts(index) -= incr; _countsTotal -= incr; assert(_counts(index) >= 0.0); assert(_countsTotal >= 0.0) }
    def redo = { _counts(index) += incr; _countsTotal += incr }
  }
  case class IncrementableCountsSeqDiff(cs: { def apply(i:Int):Double; def length:Int }) extends Diff {
    def variable = IncrementableCounts.this
    def undo = { for (i <- 0 until cs.length) { _counts(i) -= cs(i); _countsTotal -= cs(i) } }
    def redo = { for (i <- 0 until cs.length) { _counts(i) += cs(i); _countsTotal += cs(i) } }
  }
}

trait ArrayIncrementableCounts extends IncrementableCounts {
  protected val _counts = new scala.collection.mutable.WrappedArray.ofDouble(Array[Double](this.length))
}

trait HashIncrementableCounts extends IncrementableCounts {
  protected val _counts = new IndexedSeq[Double] {
    private val h = new scala.collection.mutable.HashMap[Int,Double] // new it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
    def length: Int = h.size
    def apply(key:Int): Double = h(key)
    def update(key:Int, value:Double): Unit = h.put(key, value)
    def zero = h.clear
  }
  override def zero(implicit d:DiffList = null): Unit = {
    if (d ne null) d += IncrementableCountsSeqDiff(_counts.map(d => -d))
    _counts.zero
  }
}


class DenseCountsProportions(len:Int) extends MutableProportions with Estimation[DenseCountsProportions] {
  def this(p:Seq[Double]) = { this(p.length); this.set(p)(null) }
  protected var _counts = new Array[Double](len)
  protected var _countsTotal = 0.0
  def length = _counts.size
  def countsSeq: Seq[Double] = _counts.toSeq
  def counts(index:Int) = _counts(index)
  def countsTotal  = _countsTotal
  def increment(index:Int, incr:Double)(implicit d:DiffList): Unit = { 
    _counts(index) += incr; _countsTotal += incr
    assert(_counts(index) >= 0, "counts("+index+")="+_counts(index)+" after incr="+incr)
    assert(_countsTotal >= 0, "countsTotal="+_countsTotal+" after incr="+incr)
    if (d ne null) d += DenseCountsProportionsDiff(index, incr)
  }
  def increment(incrs:Seq[Double])(implicit d:DiffList): Unit = {
    forIndex(incrs.length)(i => increment(i, incrs(i)))
  }
  def set(p:Seq[Double])(implicit d:DiffList): Unit = { zero(); increment(p) }
  def apply(index:Int): Double = {
    if (_countsTotal == 0) 1.0 / length
    else _counts(index) / _countsTotal
  }
  def zero(): Unit = { java.util.Arrays.fill(_counts, 0.0); _countsTotal = 0.0 }
  //class DiscretePr(override val index:Int, override val pr:Double, val count:Double) extends super.DiscretePr(index,pr)
  //override def top(n:Int): Seq[DiscretePr] = this.toArray.zipWithIndex.sortBy({case (p,i) => -p}).take(n).toList.map({case (p,i)=>new DiscretePr(i,p,counts(i))}).filter(_.pr > 0.0)
  case class DenseCountsProportionsDiff(index:Int, incr:Double) extends Diff {
    def variable = DenseCountsProportions.this
    def undo = { _counts(index) -= incr; _countsTotal -= incr; assert(_counts(index) >= 0.0) }
    def redo = { _counts(index) += incr; _countsTotal += incr; assert(_counts(index) >= 0.0) }
  }
}

class GrowableDenseCountsProportions(initialCapacity:Int = 32) extends DenseCountsProportions(initialCapacity) {
  private var _size = 0
  override def length = _size // new Exception().printStackTrace()
  override def counts(index:Int):Double = if (index < _counts.size) _counts(index) else 0.0
  protected def ensureCapacity(size:Int): Unit = if (_counts.size < size) {
    val newSize = math.max(_counts.size * 2, size)
    //println("GrowableDenseCountsProportions "+this.hashCode+" growing from "+_counts.size+" to capacity "+size+".  New size="+newSize)
    val newCounts = new Array[Double](newSize)
    Array.copy(_counts, 0, newCounts, 0, _counts.size)
    _counts = newCounts
    //println("GrowableDenseCountsProportions "+this.hashCode+" done growing.")
  }
  override def increment(index:Int, incr:Double)(implicit d:DiffList): Unit = {
    ensureCapacity(index+1)
    //if (index >= _size) { println("GrowableDenseCountsProportions.increment growing index="+index); _size = index + 1 }
    if (index >= _size) { _size = index + 1 }
    //if (index >= _size) { _size = 100000 }
    super.increment(index, incr)
    //super.increment(_size, incr)
  }
}


object DenseProportions {
  implicit val denseProportionsEstimator = new Estimator[DenseProportions] {
    def estimate(p:DenseProportions, model:Model): Unit = {
      val counts = new Array[Double](p.length)
      for (child <- p.children) child match { case child:DiscreteVar => counts(child.intValue) = counts(child.intValue) + 1.0 }
      for (i <- 0 until p.length) counts(i) /= p.children.size
      p.set(counts)(null) // TODO Should we have a DiffList here?
    }
  }
}


object DenseCountsProportions {
  implicit val denseCountsProportionsEstimator = new Estimator[DenseCountsProportions] {
    def estimate(p:DenseCountsProportions, model:Model): Unit = {
      p.zero
      for (child <- p.children) child match { case child:DiscreteVar => p.increment(child.intValue, 1.0)(null) }
    }
  }
}



//trait SparseVectorIncrementableCounts extends IncrementableCounts {
//  protected val _counts = new SparseVector(this.length) { def length = size }
//}



// Multinomial for which sampling is efficient because outcomes are considered in order of highest-count first.
//  This implementation is not yet finished.
/*@deprecated // Not finished
abstract class SortedSparseCountsProportions(dim:Int) extends CountsProportions {
  def length: Int = pos.length
  private var total: Int = 0 // total of all counts in buf
  // Make sure we have enough bits to represent the dimension of the multinomial
  private val topicMask = if (Integer.bitCount(dim) == 1) dim-1 else Integer.highestOneBit(dim) * 2 - 1
  private val topicBits = Integer.bitCount(topicMask)
  private var bufsize = 32
  private var siz = 0 // current size of buf 
  private val buf = new Array[Int](bufsize) // stores both count and topic packed into a single Int, indexed by pos
  assert (dim < Math.MAX_SHORT)
  private val pos = new Array[Short](dim); for (i <- 0 until dim) pos(i) = -1 // mapping from index to pos in count 
  private def ti(pos:Int) = buf(pos) & topicMask // topic at position 
  private def co(pos:Int) = buf(pos) >> topicBits // count at position
  def incrementCount(index:Int, incr:Int): Unit = { val p:Int = pos(index); buf(p) = (co(p) + incr) }
}*/





// The binary special case, for convenience

/** The outcome of a coin flip, with boolean value.  */
class Flip(coin:Coin, value:Boolean = false) extends BooleanVariable(value) with GeneratedDiscreteVariable {
  def proportions = coin
  coin.addChild(this)(null)
}
/** A coin, with Multinomial distribution over outcomes, which are Flips. */
class Coin(p:Double) extends DenseProportions(Seq(1.0-p, p)) {
  def this() = this(0.5)
  assert (p >= 0.0 && p <= 1.0)
  def flip: Flip = { val f = new Flip(this); f.set(this.sampleInt)(null); f }
  def flip(n:Int) : Seq[Flip] = for (i <- 0 until n) yield flip
}
object Coin { 
  def apply(p:Double) = new Coin(p)
}