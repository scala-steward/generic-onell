package ru.ifmo.onell.problem.mst

import java.util.concurrent.{ThreadLocalRandom => Random}

import ru.ifmo.onell.util.{DisjointSet, OrderedSet, Permutation}
import ru.ifmo.onell.{Fitness, HasIndividualOperations}

import TreeOnlyMST._

class TreeOnlyMST(nVertices: Int, edges: IndexedSeq[Edge])
  extends Fitness[Individual, Long, Long] with HasIndividualOperations[Individual]
{
  private[this] val (optimalAnswer, nConnectivityComponents) = solveMST(nVertices, edges)
  private[this] val penalty: Long = math.max(1, edges.view.map(e => math.max(0, e.weight.toLong)).sum)

  assert(edges.forall(e => e.vertexA >= 0 && e.vertexB >= 0 && e.vertexA < nVertices && e.vertexB < nVertices),
         "Some of vertex indices are illegal")
  assert(nConnectivityComponents == 1,
         "The edge set is not connected") // maybe add penalty * nConnectivityComponents to the optimal answer?

  override def evaluate(individual: Individual): Long = individual.fitness()
  override def compare(lhs: Long, rhs: Long): Int = rhs.compareTo(lhs)
  override def worstFitness: Long = penalty * nVertices
  override def problemSize: Int = edges.size
  override def isOptimalFitness(fitness: Long): Boolean = fitness == optimalAnswer

  override def numberOfChanges: Long = (nVertices - 1L) * (edges.size - nVertices + 1)
  override def changeIndexTypeToLong(st: Long): Long = st

  override def applyDelta(ind: Individual, delta: OrderedSet[Long], currentFitness: Long): Long = {
    val size = delta.size
    var i = 0
    while (i < size) {
      ind.flipPair(delta(i))
      i += 1
    }
    ind.fitness()
  }

  override def unapplyDelta(ind: Individual, delta: OrderedSet[Long]): Unit = {
    var i = delta.size
    while (i > 0) {
      i -= 1
      ind.flipPair(delta(i))
    }
  }

  override def createStorage(problemSize: Int): Individual = new Individual(nVertices, edges.size, edges, penalty)
  override def initializeRandomly(individual: Individual, rng: Random): Unit = individual.initializeRandomly(rng)
}

object TreeOnlyMST {
  case class Edge(vertexA: Int, vertexB: Int, weight: Int)

  def randomGraph(nVertices: Int, nEdges: Int, minWeight: Int, maxWeight: Int, rng: Random): TreeOnlyMST = {
    val ds = new DisjointSet(nVertices)
    val builder = IndexedSeq.newBuilder[Edge]
    var components = nVertices
    while (components > 1) {
      val e = Edge(vertexA = rng.nextInt(nVertices),
                   vertexB = rng.nextInt(nVertices),
                   weight = rng.nextInt(minWeight, maxWeight + 1))
      if (ds.unite(e.vertexA, e.vertexB)) {
        builder += e
        components -= 1
      }
    }

    for (_ <- nVertices - 1 until nEdges)
      builder += Edge(vertexA = rng.nextInt(nVertices),
                      vertexB = rng.nextInt(nVertices),
                      weight = rng.nextInt(minWeight, maxWeight + 1))

    new TreeOnlyMST(nVertices, builder.result())
  }

  class Individual(nVertices: Int, nEdges: Int, edges: IndexedSeq[Edge], penaltyForComponent: Long) {
    private[this] var edgeOrder: Permutation = _
    private[this] val ds = new DisjointSet(nVertices)

    def initializeRandomly(rng: Random): Unit = {
      if (edgeOrder == null)
        edgeOrder = Permutation.identity(nEdges)
      Permutation.shuffle(edgeOrder, rng)
    }

    def flipPair(edgePairIndex: Long): Unit = {
      checkWhetherInitialized()
      val nChosenEdges = nVertices - 1
      val indexInChosen = (edgePairIndex % nChosenEdges).toInt
      val indexInNonChosen = (edgePairIndex / nChosenEdges).toInt + nChosenEdges
      edgeOrder.swap(indexInChosen, indexInNonChosen)
    }

    def fitness(): Long = {
      ds.clear()

      var i = 0
      var theFitness = (nVertices - 1) * penaltyForComponent

      while (i + 1 < nVertices) {
        val e = edges(edgeOrder(i))
        if (ds.unite(e.vertexA, e.vertexB)) {
          theFitness -= penaltyForComponent
        }
        theFitness += e.weight
        i += 1
      }

      theFitness
    }

    private def checkWhetherInitialized(): Unit =
      if (edgeOrder == null)
        throw new IllegalStateException("Individual shall be initialized!")
  }

  def solveMST(nVertices: Int, edges: IndexedSeq[Edge]): (Long, Int) = {
    val ds = new DisjointSet(nVertices)
    val sortedEdges = edges.sortBy(_.weight)
    var weight = 0L
    var components = nVertices
    var i = 0
    while (i < sortedEdges.size) {
      val e = sortedEdges(i)
      if (ds.unite(e.vertexA, e.vertexB)) {
        components -= 1
        weight += e.weight
      }
      i += 1
    }
    (weight, components)
  }
}