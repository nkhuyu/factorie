/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.example

import cc.factorie._
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import java.io.File
import java.util.Random

/** Example of model for dependency parsing.
    Not finished.  Overly simple inference (just sampling!), projectivity not maintained.
    But interesting example of flexibility of the "Template.statistics" method. */

object DepParsing1 {

  object TokenDomain extends CategoricalVectorDomain[String]
  class Token(val word:String, posString:String, trueParentPosition:Int, trueLabelString:String) extends BinaryFeatureVectorVariable[String] with ChainLink[Token,Sentence] {
    def domain = TokenDomain
    val node = new Node(this, trueParentPosition, trueLabelString)
    val pos = new POS(posString)
    override def toString = "Token("+word+":"+position+")"
  }

  class Node(val token:Token, val truePosition:Int, trueLabelString:String) extends RefVariable[Token] with VarWithTargetValue with IterableSettings {
    lazy val trueValue: Token = seq(truePosition)
    def seq: Seq[Token] = token.chain
    val label = new Label(trueLabelString)
    def valueIsTarget = value == trueValue
    def setToTarget(implicit d:DiffList): Unit = set(trueValue)
    def parent = value
    /** Bool with value true if parent is to the right, false if parent is to the left. */
    def direction: Direction = if (parent.position > token.position) new Direction(true) else new Direction(false)
    def distance: Distance = new Distance(math.abs(parent.position - token.position))
    def position = value.position
    // Preserve projectivity when setting a new parent.  Not yet implemented.
    /*override def set(newParent:Token)(implicit d:DiffList): Unit = {
      super.set(newParent)
      val min = math.min(token.position, parent.position)
      val max = math.max(token.position, parent.position)
      for (b <- token.between(newParent)) {
        val bparent = b.parent
        if (bparent.value != null && (bparent.position < min || bparent.position > max))
          bparent.set(seq(Global.random.nextInt(max-min+1)+min))
      }
    }*/
    def setRandomly: Unit = { this := seq.sampleUniformly }
    def settings = new SettingIterator {
      var i = -1
      val max = token.chain.length - 1
      def hasNext = i < max // && !(i == token.position && i+1 == max)
      def next(difflist:DiffList) = { 
        i += 1; //if (i == token.position) i += 1;
        val d = newDiffList;
        //println("next seq.length="+seq.length)
        set(Node.this.seq(i))(d); 
        d 
      }
      def reset = i = -1
      override def variable : Node = Node.this
    }
  }

  class Direction(right:Boolean) extends BooleanVariable(right)

  object DistanceDomain extends DiscreteDomain { def size = 6 }
  class Distance(d:Int) extends DiscreteVariable {
    def domain = DistanceDomain
    if (d < domain.size-1) set(d)(null) else set(domain.size-1)(null)
  }

  object POSDomain extends CategoricalDomain[String]
  class POS(posString:String) extends CategoricalVariable(posString) { def domain = POSDomain }
  object LabelDomain extends CategoricalDomain[String]
  class Label(trueString:String) extends LabelVariable(trueString) { def domain = LabelDomain }
  class Sentence extends Chain[Sentence,Token] {
    this += new Token("<ROOT>", ".", 0, "ROOT")
  }
  
  val templateModel = new TemplateModel(
    /*new Template2[Node,Token] with DotStatistics2[Token#ValueType,Token#ValueType] with SparseWeights {
      def unroll1(n:Node) = Factor(n, n.token)
      def unroll2(t:Token) = Nil
      // Node.value = parent.  parent.value:Token#ValueType
      def statistics(values:Values) = Stat(values._1.value, values._2)
      //def statistics(parent:Token, childVector:Token#ValueType) = Stat(parent.value, childVector)
    },
    */
    new Template3[Node,Token,Token] with DotStatistics2[Token#ValueType,Token#ValueType] with SparseWeights {
      def unroll1(n:Node) = Factor(n, n.parent, n.token)
      def unroll2(t:Token) = Nil
      def unroll3(t:Token) = Nil
      def statistics(values:Values) = Stat(values._2, values._3)
      //def statistics(n:Node#Value, parentVector:Token#Value, childVector:Token#ValueType) = Stat(parentVector, childVector)
    },
    /*new Template3WithDotStatistics2[Token,Token,Node] {
      def unroll3(node:Node) = ...
    }
    new Template2WithDotStatistics1[DiscreteVectorVar,Node] {
      def unroll2(n:Node) = Factor(n.parent outer n.child, n)
    }
    new Template2WithDotStatistics1[VectorVariables2[Token,Token],Node] {
      def unroll2(n:Node) = Factor(VectorVariables2(n.parent, n.token), n)
    }
    new Template2[VectorVariables2[Token,Token], Node] with FirstObserved with StatsAreFirstArg {
      def unroll2(node:Node) = Factor(VectorVariables2(n.parent, n.token), n)
    }*/
    //new Template1[Node] with DotStatistics2[Token,Token] with SparseWeights { def statistics(n:Node) = Stat(n.token, n.parent) },

    new Template2[Node,POS] with DotStatistics1[POS#Value] {
      def unroll1(n:Node) = Factor(n, n.token.pos)
      def unroll2(p:POS) = Nil
      def statistics(values:Values) = Stat(values._2)
      //def statistics(n:Node#ValueType, pos:POS#ValueType) = Stat(pos)
    },
    //new Template1[Node] with DotStatistics1[POS] {
    //override def _statistics(n:Node) = Stat(token.pos)
    //def statistics(nv:Node#ValueType) = new Error
    //},
    //new Template1[Node] with DotStatistics1[POS] { def statistics(n:Node) = Stat(token.pos) },



    new Template1[Node] with DotStatistics1[POS#Value] { def statistics(v:Values) = Stat(v._1.node.token.pos.value) },
    new Template1[Node] with DotStatistics1[Token#Value] { def statistics(v:Values) = Stat(v._1.value) },
    new Template1[Node] with DotStatistics1[Token#Value] { def statistics(v:Values) = Stat(v._1.node.token.value) },
    new Template1[Node] with DotStatistics2[POS#Value,POS#Value] { def statistics(v:Values) = Stat(v._1.pos.value, v._1.node.token.pos.value) },
    new Template1[Node] with DotStatistics2[POS#Value,Token#Value] with SparseWeights { def statistics(v:Values) = Stat(v._1.pos.value, v._1.node.token.value) },
//
//    new Template1[Node] with DotStatistics2[Token#Value,POS#Value] with SparseWeights { def statistics(v:Values) = Stat(n.token, n.parent.pos) },
//    new Template1[Node] with DotStatistics3[POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = for (b <- n.token.between(n.parent)) yield Stat(n.token.pos, b.pos, n.parent.pos) },
//    new Template1[Node] with DotStatistics3[POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.token.hasPrev) Stat(n.token.prev.pos, n.token.pos, n.parent.pos) else Nil },
//    new Template1[Node] with DotStatistics3[POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.token.hasNext) Stat(n.token.pos, n.token.next.pos, n.parent.pos) else Nil },
//    new Template1[Node] with DotStatistics3[POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.parent.hasPrev) Stat(n.token.pos, n.parent.prev.pos, n.parent.pos) else Nil },
//    new Template1[Node] with DotStatistics3[POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.parent.hasNext) Stat(n.token.pos, n.parent.pos, n.parent.next.pos) else Nil },
//    new Template1[Node] with DotStatistics4[POS#Value,POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.parent.hasNext && n.token.hasNext) Stat(n.token.pos, n.token.next.pos, n.parent.pos, n.parent.next.pos) else Nil },
//    new Template1[Node] with DotStatistics4[POS#Value,POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.parent.hasNext && n.token.hasPrev) Stat(n.token.pos, n.token.prev.pos, n.parent.pos, n.parent.next.pos) else Nil },
//    new Template1[Node] with DotStatistics4[POS#Value,POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.parent.hasPrev && n.token.hasNext) Stat(n.token.pos, n.token.next.pos, n.parent.pos, n.parent.prev.pos) else Nil },
//    new Template1[Node] with DotStatistics4[POS#Value,POS#Value,POS#Value,POS#Value] { def statistics(v:Values) = if (n.parent.hasPrev && n.token.hasPrev) Stat(n.token.pos, n.token.prev.pos, n.parent.pos, n.parent.prev.pos) else Nil },
//
    new Template1[Node] with DotStatistics2[Token#ValueType,Direction#Value] with SparseWeights { def statistics(v:Values) = Stat(v._1.node.token.value, v._1.node.direction.value) }, // is parent.token right for direction?
    new Template1[Node] with DotStatistics2[Direction#Value,Distance#Value] { def statistics(v:Values) = Stat(v._1.node.direction.value, v._1.node.distance.value) },
    new Template1[Node] with DotStatistics1[Direction#Value] { def statistics(v:Values) = Stat(v._1.node.direction.value) },
    new Template1[Node] with DotStatistics1[Distance#Value] { def statistics(v:Values) = Stat(v._1.node.distance.value) },
    new Template1[Node] with DotStatistics3[POS#Value,Direction#Value,Distance#Value] { def statistics(v:Values) = Stat(v._1.pos.value, v._1.node.direction.value, v._1.node.distance.value) },
    new Template1[Node] with DotStatistics3[POS#Value,POS#Value,Direction#Value] { def statistics(v:Values) = Stat(v._1.pos.value, v._1.node.token.pos.value, v._1.node.direction.value) },
    new Template1[Node] with DotStatistics4[POS#Value,POS#Value,Direction#Value,Distance#Value] { def statistics(v:Values) = Stat(v._1.pos.value, v._1.node.token.pos.value, v._1.node.direction.value, v._1.node.distance.value) }
  )

  val factorModel = new Model {
    def factors(vars: Iterable[Variable]): Seq[Factor] = Seq(new Factor {
      def variable(i: Int): Variable = null
      val statistics = new Statistics {
        override def score: Double = {
          var ret = 0.0
          vars.foreach(va => {
            val v = va.asInstanceOf[Node]
            val s = collection.mutable.HashSet[Int]()
            var vv = v.parent.node
            //println("Inspecting position "+v.token.position+" with parent "+vv.token.position)
            if (v.token.position == 0) {
              if (v.parent.node.token.position != 0) ret -= 100// Double.NegativeInfinity
            } else {
              while(! s.contains(vv.token.position)) {
                s.add(vv.token.position)
                vv = vv.parent.node
                //println("now looking at position "+vv.token.position)
              }
              if (vv.token.position == v.token.position) {
                //println ("setting parent to "+vv.token.position );
                ret -= 100// Double.NegativeInfinity
              }
            }
          })
          //println ("returning "+ret)
          ret
        }

      }
      val values: Values = null
      def valuesIterator(v: Set[Variable]): Iterator[Values] = null
      val numVariables = 0
      
    })
  }



  val model = new CombinedModel(templateModel, factorModel)
  
  val obj = new TemplateModel(
    new TemplateWithStatistics2[Node,BooleanVar] {
      def unroll1(n:Node) = Factor(n, new BooleanVariable(n.valueIsTarget))
      def unroll2(b:BooleanVar) = Nil
      def score(s:Stat) = {
        val valueIsTarget = s._2
        if (valueIsTarget.booleanValue) 1.0 else 0.0
        //-math.abs(node.position - node.truePosition)
      }
    }
  )

  val objective = new CombinedModel(obj, factorModel)
  
  def main(args:Array[String]): Unit = {
    val datafile = if (args.length > 0) args(0) else "/Users/apassos/data/deptrain.owpl"
    val sentences = new ArrayBuffer[Sentence];
    var sentence = new Sentence
    val source = Source.fromFile(new File(datafile))
    for (line <- source.getLines()) {
      if (line.length < 2) {
        if (sentence.length > 0) { sentences += sentence; sentence = new Sentence }
      } else {
        val fields = line.split("\\s+")
        val word = fields(2)
        val pos = fields(3)
        val parentPosition = Integer.parseInt(fields(8))
        val token = new Token(word, pos, parentPosition, fields(9))
        val w = simplify(word)
        token += w //.substring(0, math.min(w.length, 6))
        //token += "POS="+pos
        sentence += token
      }
    }
    println("Read "+sentences.length+" sentences, "+sentences.foldLeft(0)(_+_.length)+" words.")
    println("Domain[Token] size = "+TokenDomain.dimensionSize)
    println("Domain[POS] size = "+POSDomain.size)
    println("Domain[Distance] size = "+DistanceDomain.size)
    
    val nodes = sentences.flatMap(_.map(_.node))
    nodes.foreach(n => n.set(n.token.seq.head)(null))
    nodes.foreach(n => n.setRandomly)
    val learner = new VariableSettingsSampler[Node](model, objective) with SampleRank with AROWUpdates {
      temperature = 0.01
      override def postIterationHook(): Boolean = {
        for (sentence <- sentences.take(20)) printSentence(sentence)
        println("Average score = "+objective.aveScore(nodes))
        super.postIterationHook
      }
      override def proposalsHook(proposals:Seq[Proposal]): Unit = {
        //proposals.foreach(p => println("%-6f %-6f %s".format(p.modelScore, p.objectiveScore, p.diff.toString)))
        val bestModel = proposals.maxByDouble(_.modelScore)
        val bestObjective = proposals.maxByDouble(_.objectiveScore)
        if (rng.nextInt(200) == 0) {
          println(bestModel.diff)
          println(bestObjective.diff)
          println("modelBest modelScore "+bestModel.modelScore)
          println("modelBest objecScore "+bestModel.objectiveScore)
          println("objecBest modelScore "+bestObjective.modelScore)
          println("objecBest objecScore "+bestObjective.objectiveScore)
        }
        super.proposalsHook(proposals)
      }
      val rng = new java.util.Random()
      override def postProcessHook(n:Node, d:DiffList): Unit = {
        printSentence(n.token.chain)
        super.postProcessHook(n,d)
      }
    }
    learner.processAll(nodes, 10)
    
  }
  
  case class Entry(score:Double, r:Int)
  def eisner(model:TemplateModel, sentence:Seq[Node]): Unit = {
    val length = sentence.length
    // Score of setting i's parent to j.
    val score = Array.tabulate(length-1, length-1)((i,j) => {
      val d = new DiffList
      sentence(i).set(null)(null) // just to make sure that we populate the DiffList
      sentence(i).set(sentence(j).token)(d)
      d.score(model)
    })
    // Chart of incomplete items, right and left
    val ir = Array.ofDim[Entry](length,length)
    val il = Array.ofDim[Entry](length,length)
    // Chart of complete items, right and left
    val cr = Array.ofDim[Entry](length,length)
    val cl = Array.ofDim[Entry](length,length)
    // Initialize the chart
    for (s <- 0 until length) {
      il(s)(s) = Entry(0.0, s)
      ir(s)(s) = Entry(0.0, s)
      cl(s)(s) = Entry(0.0, s)
      cr(s)(s) = Entry(0.0, s)
    }
    // Fill the chart
    for (k <- 0 until length; s <- 0 until length; val t = s + k; if (t < length)) {
      il(s)(t) = (for (r <- s until t) yield Entry(cr(s)(r).score + cl(r+1)(t).score + score(t)(s), r)).maxByDouble(_.score)
      ir(s)(t) = (for (r <- s until t) yield Entry(cr(s)(r).score + cl(r+1)(t).score + score(s)(t), r)).maxByDouble(_.score)
      cl(s)(t) = (for (r <- s until t) yield Entry(cl(s)(r).score + il(r)(t).score, r)).maxByDouble(_.score)
      cr(s)(t) = (for (r <- s+1 to  t) yield Entry(ir(s)(r).score + cr(r)(t).score, r)).maxByDouble(_.score)
    }
    // Use the chart to set the parents, recursively
    def setParents(start:Int, end:Int, parent:Token): Unit = {
      val mid = cr(start)(end).r
      sentence(mid).set(parent)(null)
      setParents(start, mid, sentence(mid).token)
      setParents(mid, end, sentence(mid).token)
    }
    setParents(0, length-1, sentence(0).token)
  }
  
  def printSentence(sentence:Seq[Token]): Unit = {
    for (token <- sentence) println("%-3d %-20s %-3d %-3d %s".
        format(token.position, token.word, token.node.truePosition, token.node.position,
        if (token.node.valueIsTarget) " " else "*"))
    println("#correct = "+objective.score(sentence.map(_.node)))
    println
  }
  
  def simplify(word:String): String = {
    if (word.matches("(19|20)\\d\\d")) "<YEAR>" 
    else if (word.matches("\\d+")) "<NUM>"
    else if (word.matches(".*\\d.*")) word.replaceAll("\\d","#").toLowerCase
    else word.toLowerCase
  }
}
