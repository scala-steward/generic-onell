package ru.ifmo.onell.delta

import java.util.Random

import ru.ifmo.onell.HasDeltaOperations
import ru.ifmo.onell.util.{BinomialScanner, IntArraySet, IntSet}

object IntSetOps extends HasDeltaOperations[IntSet] {
  override def createStorage(problemSize: Int): IntSet = new IntArraySet(problemSize)

  override def initializeDeltaWithDefaultSize(delta: IntSet, problemSize: Int, expectedSize: Double, rng: Random): Int = {
    delta.clear()

    val scanner = BinomialScanner(expectedSize / problemSize)
    var index = scanner.offset(rng) - 1
    while (index < problemSize) {
      delta.add(index)
      index += scanner.offset(rng)
    }

    delta.size
  }

  override def initializeDeltaWithGivenSize(delta: IntSet, problemSize: Int, size: Int, rng: Random): Unit = {
    delta.clear()
    while (delta.size < size) {
      delta.add(rng.nextInt(problemSize))
    }
  }

  override def initializeDeltaFromExisting(delta: IntSet, source: IntSet, expectedSize: Double, rng: Random): Int = {
    delta.clear()

    val sourceSize = source.size
    val scanner = BinomialScanner(expectedSize / sourceSize)
    var index = scanner.offset(rng) - 1
    while (index < sourceSize) {
      delta.add(source(index))
      index += scanner.offset(rng)
    }

    delta.size
  }

  override def copyDelta(source: IntSet, target: IntSet): Unit = {
    target.clear()
    val size = source.size
    var i = 0
    while (i < size) {
      target.add(source(i))
      i += 1
    }
  }
}