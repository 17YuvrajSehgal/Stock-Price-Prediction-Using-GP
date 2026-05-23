package ensemble;

import backtest.BacktestResult;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.koza.KozaFitness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Selects a decorrelated set of "champion" individuals from the final population.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Sort the population by standardized fitness (lower is better).</li>
 *   <li>Take the top {@code candidatePoolSize} candidates.</li>
 *   <li>Run a caller-supplied evaluator to get each candidate's out-of-sample
 *       signal vector and backtest result.</li>
 *   <li>Greedily admit candidates whose pairwise signal-vector Pearson
 *       correlation with every already-admitted champion is below
 *       {@code maxCorrelation}. Stop at {@code desiredCount}.</li>
 * </ol>
 *
 * <p>The point of decorrelation is ensemble diversity — admitting a candidate
 * that trades the same way as the existing champions adds no robustness. A
 * common threshold for "meaningfully different" is correlation &lt; 0.7.
 */
public final class ChampionPool {

    private static final double EPS = 1.0e-12;

    private ChampionPool() {
    }

    @FunctionalInterface
    public interface OosEvaluator {
        OosEvaluation evaluate(GPIndividual individual);
    }

    public static List<Champion> selectDecorrelated(Individual[] population,
                                                    OosEvaluator evaluator,
                                                    int candidatePoolSize,
                                                    int desiredCount,
                                                    double maxCorrelation) {
        if (population == null || population.length == 0) return List.of();

        Individual[] sorted = population.clone();
        Arrays.sort(sorted, Comparator.comparingDouble(ChampionPool::standardizedFitness));

        int poolSize = Math.min(candidatePoolSize, sorted.length);
        List<Champion> selected = new ArrayList<>();

        for (int rank = 0; rank < poolSize && selected.size() < desiredCount; rank++) {
            if (!(sorted[rank] instanceof GPIndividual gp)) continue;
            OosEvaluation ev;
            try {
                ev = evaluator.evaluate(gp);
            } catch (RuntimeException e) {
                continue;
            }
            if (ev == null || ev.signalVector == null || ev.signalVector.length == 0) continue;

            double maxCorrWithSelected = 0.0;
            boolean rejected = false;
            for (Champion existing : selected) {
                double c = Math.abs(pearson(ev.signalVector, existing.signalVector));
                if (c > maxCorrWithSelected) maxCorrWithSelected = c;
                if (c >= maxCorrelation) {
                    rejected = true;
                    break;
                }
            }
            if (rejected) continue;

            selected.add(new Champion(
                    selected.size() + 1,
                    rank + 1,
                    standardizedFitness(gp),
                    totalNodes(gp),
                    expressions(gp),
                    ev.result,
                    ev.signalVector,
                    selected.isEmpty() ? 0.0 : maxCorrWithSelected));
        }

        return selected;
    }

    static double pearson(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 2) return 0.0;
        double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;
        for (int i = 0; i < n; i++) {
            sumA += a[i];
            sumB += b[i];
            sumAA += (double) a[i] * a[i];
            sumBB += (double) b[i] * b[i];
            sumAB += (double) a[i] * b[i];
        }
        double cov = sumAB - sumA * sumB / n;
        double varA = sumAA - sumA * sumA / n;
        double varB = sumBB - sumB * sumB / n;
        if (varA < EPS || varB < EPS) return 0.0;
        return cov / Math.sqrt(varA * varB);
    }

    private static double standardizedFitness(Individual ind) {
        if (ind == null || ind.fitness == null) return Double.POSITIVE_INFINITY;
        if (ind.fitness instanceof KozaFitness kf) return kf.standardizedFitness();
        return Double.POSITIVE_INFINITY;
    }

    private static int totalNodes(GPIndividual gp) {
        int n = 0;
        for (var tree : gp.trees) n += tree.child.numNodes(0);
        return n;
    }

    private static List<String> expressions(GPIndividual gp) {
        List<String> out = new ArrayList<>(gp.trees.length);
        for (var tree : gp.trees) out.add(tree.child.makeCTree(true, true, true));
        return out;
    }

    // -- value objects --------------------------------------------------------

    public static final class OosEvaluation {
        public final int[] signalVector;
        public final BacktestResult result;

        public OosEvaluation(int[] signalVector, BacktestResult result) {
            this.signalVector = signalVector;
            this.result = result;
        }
    }

    public static final class Champion {
        public final int rank;                       // 1..desiredCount in selection order
        public final int populationRank;             // 1..N rank in the sorted population
        public final double standardizedFitness;
        public final int totalNodes;
        public final List<String> treeExpressions;
        public final BacktestResult result;
        public final int[] signalVector;
        public final double maxCorrelationWithPrior;

        Champion(int rank, int populationRank, double standardizedFitness, int totalNodes,
                 List<String> treeExpressions, BacktestResult result, int[] signalVector,
                 double maxCorrelationWithPrior) {
            this.rank = rank;
            this.populationRank = populationRank;
            this.standardizedFitness = standardizedFitness;
            this.totalNodes = totalNodes;
            this.treeExpressions = treeExpressions;
            this.result = result;
            this.signalVector = signalVector;
            this.maxCorrelationWithPrior = maxCorrelationWithPrior;
        }
    }
}
