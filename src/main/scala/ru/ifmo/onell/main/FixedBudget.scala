package ru.ifmo.onell.main

import java.util.concurrent.ThreadLocalRandom
import java.util.{Locale, Random}

import ru.ifmo.onell.algorithm.OnePlusOneEA
import ru.ifmo.onell.algorithm.oll.CompatibilityLayer._
import ru.ifmo.onell.distribution.PowerLawDistribution
import ru.ifmo.onell.problem.RandomPlanted3SAT._
import ru.ifmo.onell.problem.{MultiDimensionalKnapsack, RandomPlanted3SAT, SimpleKnapsack}
import ru.ifmo.onell.util.Specialization.{fitnessSpecialization => fsp}
import ru.ifmo.onell.{problem, _}

object FixedBudget extends Main.Module {
  override def name: String = "fixed-budget"
  override def shortDescription: String = "Runs experiments on expected fitness values given the budget"
  override def longDescription: Seq[String] = Seq(
    "Runs experiments on expected fitness values given the budget.",
    "The current implementation runs only the (1+1) EA and the (1+(λ,λ)) GA with different tuning approaches for λ",
    "on hard MAX-SAT instances, the 0-1 knapsack problem and the multidimensional knapsack problem.",
    "Parameters:",
    "  - sat: run the experiment on hard MAX-SAT instances",
    "  - knapsack: run the experiment on the 0-1 knapsack problem",
    "  - mkp: run the experiment on the multidimensional knapsack problem"
  )

  override def moduleMain(args: Array[String]): Unit = {
    Locale.setDefault(Locale.US)
    args(0) match {
      case "sat" => runHardSat((4 to 28).map(v => v * v))
      case "knapsack" => runSimpleKnapsack()
      case "mkp" => runMultiDimensionalKnapsack()
      case _ => throw new IllegalArgumentException(s"Unknown command for $name: ${args(0)}")
    }
  }

  private case class BudgetReached[F](fitness: F) extends RuntimeException
  private case class RestartConditionReached[F](fitness: F, evaluations: Long) extends RuntimeException

  private class TerminationConditionTracker[@specialized(fsp) F](ff: Fitness[_, F, _], budget: Long)
    extends IterationLogger[F]
  {
    private[this] var lastLambda = 0.0
    private[this] var bestFitness: F = ff.worstFitness

    def attachedTuning(realTuning: Long => LambdaTuning)(size: Long): LambdaTuning = new LambdaTuning {
      private[this] val delegate = realTuning(size)
      override def lambda(rng: ThreadLocalRandom): Double = {
        lastLambda = delegate.lambda(rng)
        lastLambda
      }

      override def notifyChildIsBetter(budgetSpent: Long): Unit = delegate.notifyChildIsBetter(budgetSpent)
      override def notifyChildIsEqual(budgetSpent: Long): Unit = delegate.notifyChildIsEqual(budgetSpent)
      override def notifyChildIsWorse(budgetSpent: Long): Unit = delegate.notifyChildIsWorse(budgetSpent)
    }

    override def logIteration(evaluations: Long, fitness: F): Unit = {
      bestFitness = ff.max(bestFitness, fitness)
      if (evaluations >= budget) {
        throw BudgetReached(bestFitness)
      }
      if (lastLambda >= ff.problemSize) {
        throw RestartConditionReached(bestFitness, evaluations)
      }
    }
  }

  private val optimizers: IndexedSeq[(String, TerminationConditionTracker[Int] => Optimizer)] = IndexedSeq(
    ("(1+1) EA aware", _ => OnePlusOneEA.Resampling),
    ("(1+1) EA unaware", _ => OnePlusOneEA.Standard),
    ("(1+1) EA aware heavy", _ => new OnePlusOneEA(n => PowerLawDistribution(n, 1.9))),
    ("uncapped unaware", t => createOnePlusLambdaLambdaGA(t.attachedTuning(defaultOneFifthLambda),
                                                          mutationStrength = 'S',
                                                          crossoverStrength = "SL",
                                                          goodMutantStrategy = 'I',
                                                          populationRounding = 'D')),
    ("uncapped aware", t => createOnePlusLambdaLambdaGA(t.attachedTuning(defaultOneFifthLambda),
                                                        mutationStrength = 'R',
                                                        crossoverStrength = "RL",
                                                        goodMutantStrategy = 'C',
                                                        populationRounding = 'D')),
    ("capped unaware", t => createOnePlusLambdaLambdaGA(t.attachedTuning(logCappedOneFifthLambda),
                                                        mutationStrength = 'S',
                                                        crossoverStrength = "SL",
                                                        goodMutantStrategy = 'I',
                                                        populationRounding = 'D')),
    ("capped aware", t => createOnePlusLambdaLambdaGA(t.attachedTuning(logCappedOneFifthLambda),
                                                      mutationStrength = 'R',
                                                      crossoverStrength = "RL",
                                                      goodMutantStrategy = 'C',
                                                      populationRounding = 'D')),
    ("heavy aware", t => createOnePlusLambdaLambdaGA(t.attachedTuning(powerLawLambda(2.9)),
                                                      mutationStrength = 'R',
                                                      crossoverStrength = "RL",
                                                      goodMutantStrategy = 'C',
                                                      populationRounding = 'D')),
  )

  private def runHardSat(problemSizes: Seq[Int]): Unit = {
    def nClausesFun(problemSize: Int) = (problemSize * 4.27).toInt

    val nInstances = 200
    val instanceSeeds = problemSizes.map(s => {
      val rng = new Random(s)
      IndexedSeq.fill(nInstances)(rng.nextLong())
    })

    for ((name, optimizer) <- optimizers) {
      print("\\addplot+ [error bars/.cd, y dir=both, y explicit] coordinates {")
      for ((problemSize, seeds) <- problemSizes.lazyZip(instanceSeeds)) {
        val nClauses = nClausesFun(problemSize)
        val results = seeds map { seed =>
          val problem = new RandomPlanted3SAT(problemSize, nClauses, HardGenerator, seed)
          runHardSat(optimizer, problem).toDouble / nClauses
        }
        val average = results.sum / results.size
        val deviation = math.sqrt(results.view.map(v => (v - average) * (v - average)).sum / (results.size - 1))
        print(f"($problemSize%d,$average%.4f)+-(0,$deviation%.3f)")
      }
      println("};")
      println(s"\\addlegendentry{$name};")
    }
  }

  private def runHardSat(optimizer: TerminationConditionTracker[Int] => Optimizer, ff: RandomPlanted3SAT): Int = {
    //noinspection NoTailRecursionAnnotation: this one cannot really be tailrec
    def runImpl(budgetRemains: Long, maxSoFar: Int): Int = {
      val tracker = new TerminationConditionTracker[Int](ff, budgetRemains)
      try {
        val iterations = optimizer(tracker).optimize(ff, tracker)
        assert(iterations <= budgetRemains)
        ff.clauseCount
      } catch {
        case BudgetReached(fitness: Int) => ff.max(maxSoFar, fitness)
        case RestartConditionReached(fitness: Int, evs) => runImpl(budgetRemains - evs, ff.max(maxSoFar, fitness))
      }
    }

    runImpl(10000, 0)
  }

  private def runMultidimensionalKnapsack(optimizer: TerminationConditionTracker[Int] => Optimizer, ff: MultiDimensionalKnapsack): Int = {
    //noinspection NoTailRecursionAnnotation: this one cannot really be tailrec
    def runImpl(budgetRemains: Long, maxSoFar: Int): Int = {
      val tracker = new TerminationConditionTracker[Int](ff, budgetRemains)
      try {
        implicit val individualOps: HasIndividualOperations[MultiDimensionalKnapsack.Individual] = ff
        optimizer(tracker).optimize(ff, tracker)
        throw new AssertionError("Cannot reach here for this problem")
      } catch {
        case BudgetReached(fitness: Int) => ff.max(maxSoFar, fitness)
        case RestartConditionReached(fitness: Int, evs) => runImpl(budgetRemains - evs, ff.max(maxSoFar, fitness))
      }
    }

    runImpl(10000, 0)
  }

  private def getDescriptor(kp: MultiDimensionalKnapsack): (Double, Int, Int) =
    (kp.tightnessRatio, kp.problemSize, kp.nConstraints)

  private def runMultiDimensionalKnapsack(): Unit = {
    val knapsacksAndSolutions = MultiDimensionalKnapsack.ChuBeaselyProblems
    for ((name, optGen) <- optimizers) {
      print("\\addplot+ [draw=none] coordinates {")
      for ((desc, subset) <- knapsacksAndSolutions.groupBy(getDescriptor)) {
        val runsForEach = 10
        val results = subset.map(p => IndexedSeq.fill(runsForEach)(runMultidimensionalKnapsack(optGen, p)).sum / p.linearRelaxation)
        val average = results.sum / runsForEach / subset.size
        print(s"({$desc},$average)")
      }
      println("};")
      println(s"\\addlegendentry{$name};")
    }
  }

  private def runSimpleKnapsack(optimizer: Optimizer, ff: SimpleKnapsack): Int = {
    //noinspection NoTailRecursionAnnotation: this one cannot really be tailrec
    def runImpl(budgetRemains: Long, maxSoFar: Int): Int = {
      val tracker = new TerminationConditionTracker[Int](ff, budgetRemains)
      try {
        implicit val individualOps: HasIndividualOperations[problem.SimpleKnapsack.Individual] = ff
        optimizer.optimize(ff, tracker)
        ff.bestFitness
      } catch {
        case BudgetReached(fitness: Int) => ff.max(maxSoFar, fitness)
        case RestartConditionReached(fitness: Int, evs) => runImpl(budgetRemains - evs, ff.max(maxSoFar, fitness))
      }
    }

    runImpl(90000, Int.MinValue)
  }

  private def runSimpleKnapsack(): Unit = {
    val runsForEach = 30

    val ollGA = createOnePlusLambdaLambdaGA(logCappedOneFifthLambda,
                                            mutationStrength = 'R',
                                            crossoverStrength = "RL",
                                            goodMutantStrategy = 'C',
                                            populationRounding = 'D')


    for (kp <- SimpleKnapsack.allProblems) {
      println(s"${kp.name} (max ${kp.bestFitness}):")
      for ((name, algo) <- Seq("(1+1) EA" -> OnePlusOneEA.Resampling, "(1+LL) GA" -> ollGA)) {
        val results = IndexedSeq.fill(runsForEach)(runSimpleKnapsack(algo, kp))
        val sum = results.map(_.toDouble).sum
        val sumSq = results.map(i => i.toDouble * i).sum
        val avg = sum / runsForEach
        val std = math.sqrt(sumSq / runsForEach - avg * avg) * math.sqrt(runsForEach / (runsForEach - 1.0))
        println(f"  $name: $avg%.02f +- $std%.02f, max ${results.max}")
      }
    }
  }
}
