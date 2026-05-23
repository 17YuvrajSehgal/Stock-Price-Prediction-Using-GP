package StockPredictor;

import backtest.BacktestConfig;
import backtest.BacktestResult;
import backtest.Backtester;
import backtest.BarRecord;
import backtest.BarSample;
import backtest.EquityPoint;
import backtest.ExecutionMode;
import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.koza.KozaFitness;
import ec.util.Parameter;
import ensemble.ChampionPool;
import feature.FeaturePipeline;
import feature.FeatureSeries;
import terminal.DoubleData;
import validation.Validator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Trading-aware GP problem. The evolved tree emits a continuous alpha score; a
 * threshold maps it to LONG / SHORT / FLAT for each bar. Fitness is computed
 * from a full backtest of those signals — risk-adjusted return (Sharpe + Calmar)
 * net of turnover, complexity, and a low-activity penalty — so evolution
 * directly optimizes profitable behavior under the configured cost model
 * instead of price-prediction MSE.
 *
 * <p>Walk-forward fitness stitches the out-of-sample segments of each rolling
 * window into a single equity stream, then scores that stream. Holdout fitness
 * runs the backtest across the full training split.
 */
public class TradingStrategyProblem extends Stock {

    public static final String P_FIT_SHARPE = "fitness.weight-sharpe";
    public static final String P_FIT_CALMAR = "fitness.weight-calmar";
    public static final String P_FIT_TURNOVER = "fitness.weight-turnover";
    public static final String P_FIT_COMPLEX = "fitness.weight-complexity";
    public static final String P_FIT_MIN_TRADES_WEIGHT = "fitness.weight-min-trades";
    public static final String P_FIT_MIN_TRADES = "fitness.min-trades";
    public static final String P_FIT_OFFSET = "fitness.offset";
    public static final String P_FIT_BARS_PER_YEAR = "fitness.bars-per-year";
    public static final String P_FIT_EXECUTION_MODE = "fitness.execution-mode";
    public static final String P_RISK_MAX_HOLDING_BARS = "risk.max-holding-bars";
    public static final String P_RISK_DAILY_LOSS_LIMIT = "risk.daily-loss-limit-pct";
    public static final String P_RISK_MAX_LEVERAGE = "risk.max-leverage";

    public static final String P_VAL_BOOTSTRAP_RESAMPLES = "validation.bootstrap-resamples";
    public static final String P_VAL_BOOTSTRAP_BLOCK = "validation.bootstrap-block-size";
    public static final String P_VAL_PERMUTATION_RESAMPLES = "validation.permutation-resamples";
    public static final String P_VAL_CROSS_SYMBOLS = "validation.cross-symbol-datasets";
    public static final String P_VAL_CHAMPION_POOL = "validation.champion-pool-size";
    public static final String P_VAL_CHAMPION_COUNT = "validation.champion-desired-count";
    public static final String P_VAL_CHAMPION_MAX_CORR = "validation.champion-max-correlation";

    private static final double DEFAULT_WEIGHT_SHARPE = 100.0;
    private static final double DEFAULT_WEIGHT_CALMAR = 20.0;
    private static final double DEFAULT_WEIGHT_TURNOVER = 50.0;
    private static final double DEFAULT_WEIGHT_COMPLEXITY = 1.0;
    private static final double DEFAULT_WEIGHT_MIN_TRADES = 100.0;
    private static final int DEFAULT_MIN_TRADES = 30;
    private static final double DEFAULT_FITNESS_OFFSET = 1000.0;
    private static final int DEFAULT_BARS_PER_YEAR = 252;

    private double weightSharpe = DEFAULT_WEIGHT_SHARPE;
    private double weightCalmar = DEFAULT_WEIGHT_CALMAR;
    private double weightTurnover = DEFAULT_WEIGHT_TURNOVER;
    private double weightComplexity = DEFAULT_WEIGHT_COMPLEXITY;
    private double weightMinTrades = DEFAULT_WEIGHT_MIN_TRADES;
    private int minTrades = DEFAULT_MIN_TRADES;
    private double fitnessOffset = DEFAULT_FITNESS_OFFSET;
    private int barsPerYear = DEFAULT_BARS_PER_YEAR;
    protected ExecutionMode fitnessExecutionMode = ExecutionMode.ALWAYS_FLAT_OVERNIGHT;
    protected int riskMaxHoldingBars = 0;
    protected double riskDailyLossLimitPct = 0.0;
    protected double riskMaxLeverage = 1.0;

    protected int valBootstrapResamples = 1000;
    protected int valBootstrapBlock = 5;
    protected int valPermutationResamples = 500;
    protected String[] valCrossSymbolPaths = new String[0];
    protected int valChampionPoolSize = 50;
    protected int valChampionDesiredCount = 5;
    protected double valChampionMaxCorrelation = 0.7;

    @Override
    public void setup(EvolutionState state, Parameter base) {
        super.setup(state, base);
        weightSharpe = state.parameters.getDoubleWithDefault(base.push(P_FIT_SHARPE), null, DEFAULT_WEIGHT_SHARPE);
        weightCalmar = state.parameters.getDoubleWithDefault(base.push(P_FIT_CALMAR), null, DEFAULT_WEIGHT_CALMAR);
        weightTurnover = state.parameters.getDoubleWithDefault(base.push(P_FIT_TURNOVER), null, DEFAULT_WEIGHT_TURNOVER);
        weightComplexity = state.parameters.getDoubleWithDefault(base.push(P_FIT_COMPLEX), null, DEFAULT_WEIGHT_COMPLEXITY);
        weightMinTrades = state.parameters.getDoubleWithDefault(base.push(P_FIT_MIN_TRADES_WEIGHT), null, DEFAULT_WEIGHT_MIN_TRADES);
        minTrades = state.parameters.getIntWithDefault(base.push(P_FIT_MIN_TRADES), null, DEFAULT_MIN_TRADES);
        fitnessOffset = state.parameters.getDoubleWithDefault(base.push(P_FIT_OFFSET), null, DEFAULT_FITNESS_OFFSET);
        barsPerYear = state.parameters.getIntWithDefault(base.push(P_FIT_BARS_PER_YEAR), null, DEFAULT_BARS_PER_YEAR);
        String mode = state.parameters.getStringWithDefault(base.push(P_FIT_EXECUTION_MODE), null, "always-flat-overnight");
        fitnessExecutionMode = parseExecutionMode(mode, state, base.push(P_FIT_EXECUTION_MODE));
        riskMaxHoldingBars = state.parameters.getIntWithDefault(base.push(P_RISK_MAX_HOLDING_BARS), null, 0);
        riskDailyLossLimitPct = state.parameters.getDoubleWithDefault(base.push(P_RISK_DAILY_LOSS_LIMIT), null, 0.0);
        riskMaxLeverage = state.parameters.getDoubleWithDefault(base.push(P_RISK_MAX_LEVERAGE), null, 1.0);

        valBootstrapResamples = state.parameters.getIntWithDefault(base.push(P_VAL_BOOTSTRAP_RESAMPLES), null, 1000);
        valBootstrapBlock = state.parameters.getIntWithDefault(base.push(P_VAL_BOOTSTRAP_BLOCK), null, 5);
        valPermutationResamples = state.parameters.getIntWithDefault(base.push(P_VAL_PERMUTATION_RESAMPLES), null, 500);
        valChampionPoolSize = state.parameters.getIntWithDefault(base.push(P_VAL_CHAMPION_POOL), null, 50);
        valChampionDesiredCount = state.parameters.getIntWithDefault(base.push(P_VAL_CHAMPION_COUNT), null, 5);
        valChampionMaxCorrelation = state.parameters.getDoubleWithDefault(base.push(P_VAL_CHAMPION_MAX_CORR), null, 0.7);
        String csv = state.parameters.getStringWithDefault(base.push(P_VAL_CROSS_SYMBOLS), null, "");
        valCrossSymbolPaths = csv.isBlank() ? new String[0] : csv.split("\\s*,\\s*");

        state.output.message("Strategy fitness weights: sharpe=" + weightSharpe
                + " calmar=" + weightCalmar
                + " turnover=" + weightTurnover
                + " complexity=" + weightComplexity
                + " min-trades-weight=" + weightMinTrades
                + " (floor=" + minTrades + ")"
                + " offset=" + fitnessOffset
                + " bars/year=" + barsPerYear
                + " execution=" + fitnessExecutionMode
                + " risk[maxHold=" + riskMaxHoldingBars
                + " dailyLoss=" + riskDailyLossLimitPct
                + " maxLev=" + riskMaxLeverage + "]");
    }

    protected BacktestConfig backtestConfigForFitness() {
        return BacktestConfig.builder()
                .initialCapital(backtestInitialCapital)
                .positionFraction(backtestPositionFraction)
                .commissionBps(backtestCommissionBps)
                .slippageBps(backtestSlippageBps)
                .barsPerYear(barsPerYear)
                .mode(fitnessExecutionMode)
                .maxHoldingBars(riskMaxHoldingBars)
                .dailyLossLimitPct(riskDailyLossLimitPct)
                .maxLeverage(riskMaxLeverage)
                .build();
    }

    @Override
    public void describe(EvolutionState state, Individual bestIndividual, int subpopulation, int threadnum, int log) {
        super.describe(state, bestIndividual, subpopulation, threadnum, log);
        if (!(bestIndividual instanceof GPIndividual gp)) return;
        if (validationMode != ValidationMode.WALK_FORWARD) return;

        List<BarSample> stitched = buildWalkForwardOosBars(state, gp, threadnum);
        if (stitched.isEmpty()) return;

        BacktestConfig cfg = backtestConfigForFitness();
        BacktestResult result = Backtester.run(cfg, stitched);

        state.output.println("Walk-forward OOS bars: " + result.barCount, log);
        state.output.println("Walk-forward OOS Sharpe: " + result.sharpe, log);
        state.output.println("Walk-forward OOS total return: " + result.totalReturnPct + "%", log);
        state.output.println("Walk-forward OOS max drawdown: " + result.maxDrawdownPct + "%", log);

        exportWalkForwardArtifacts(state, gp, result);
        runRobustnessSuite(state, gp, threadnum, stitched, cfg, result, log);
    }

    // -- Phase 4: robustness suite + cross-symbol + champion pool -------------

    private void runRobustnessSuite(EvolutionState state, GPIndividual gp, int threadnum,
                                    List<BarSample> stitched, BacktestConfig cfg,
                                    BacktestResult result, int log) {
        double[] perBarNet = perBarNetReturns(result);
        long seed = (long) state.generation * 1_000_003L + 17L;
        Validator.BootstrapResult bootstrap = Validator.blockBootstrapSharpe(
                perBarNet, valBootstrapBlock, valBootstrapResamples, barsPerYear, seed);
        Validator.PermutationResult permutation = Validator.permutationTest(
                stitched, cfg, valPermutationResamples, seed + 1);

        int populationSize = state.population != null && state.population.subpops != null
                && state.population.subpops.length > 0 ? state.population.subpops[0].individuals.length : 1;
        int trials = Math.max(2, populationSize * Math.max(1, state.generation + 1));
        double skew = Validator.sampleSkew(perBarNet);
        double excessKurt = Validator.sampleExcessKurtosis(perBarNet);
        double psr = Validator.probabilisticSharpe(result.sharpe, 0.0, perBarNet.length, skew, excessKurt);
        double dsr = Validator.deflatedSharpe(result.sharpe, trials, perBarNet.length, skew, excessKurt);

        state.output.println("Validation - bootstrap Sharpe CI: [" + bootstrap.sharpeP05 + ", " + bootstrap.sharpeMedian + ", " + bootstrap.sharpeP95 + "]", log);
        state.output.println("Validation - permutation p-value: " + permutation.pValue, log);
        state.output.println("Validation - PSR (vs 0): " + psr, log);
        state.output.println("Validation - DSR (trials=" + trials + "): " + dsr, log);

        List<CrossSymbolResult> cross = runCrossSymbolHoldout(state, gp, threadnum, cfg, log);
        List<ChampionExport> champions = buildChampionPool(state, threadnum, cfg, log);

        exportValidationArtifacts(state, gp, result, bootstrap, permutation, psr, dsr, skew, excessKurt, trials, cross);
        exportChampions(state, champions);
    }

    private double[] perBarNetReturns(BacktestResult result) {
        double[] out = new double[result.bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.bars.get(i).netStrategyReturn;
        }
        return out;
    }

    private List<CrossSymbolResult> runCrossSymbolHoldout(EvolutionState state, GPIndividual gp,
                                                          int threadnum, BacktestConfig cfg, int log) {
        List<CrossSymbolResult> out = new ArrayList<>();
        if (valCrossSymbolPaths.length == 0) return out;

        double[][] origData = this.stockData;
        String[] origTs = this.stockTimestamps;
        FeatureSeries origFs = this.featureSeries;

        for (String path : valCrossSymbolPaths) {
            if (path == null || path.isBlank()) continue;
            try {
                ParsedDataset ds = readCsv(path.trim(), state);
                this.stockData = ds.values;
                this.stockTimestamps = ds.timestamps;
                this.featureSeries = FeaturePipeline.compute(ds.values, ds.timestamps);
                List<BarSample> bars = buildBarsForRange(state, gp, threadnum, 0, ds.values.length - 1);
                if (bars.isEmpty()) {
                    out.add(new CrossSymbolResult(path, null));
                    continue;
                }
                BacktestResult r = Backtester.run(cfg, bars);
                out.add(new CrossSymbolResult(path, r));
                state.output.println("Cross-symbol " + path + ": Sharpe=" + r.sharpe
                        + " return%=" + r.totalReturnPct + " maxDD%=" + r.maxDrawdownPct
                        + " trades=" + r.trades, log);
            } catch (RuntimeException e) {
                state.output.warning("Cross-symbol holdout failed for " + path + ": " + e.getMessage());
                out.add(new CrossSymbolResult(path, null));
            }
        }

        this.stockData = origData;
        this.stockTimestamps = origTs;
        this.featureSeries = origFs;
        return out;
    }

    private List<ChampionExport> buildChampionPool(EvolutionState state, int threadnum,
                                                   BacktestConfig cfg, int log) {
        List<ChampionExport> out = new ArrayList<>();
        if (state.population == null || state.population.subpops == null
                || state.population.subpops.length == 0) {
            return out;
        }
        Individual[] population = state.population.subpops[0].individuals;

        ChampionPool.OosEvaluator evaluator = individual -> {
            List<BarSample> bars = buildWalkForwardOosBars(state, individual, threadnum);
            if (bars.isEmpty()) return null;
            int[] signals = new int[bars.size()];
            for (int i = 0; i < bars.size(); i++) signals[i] = bars.get(i).signal;
            BacktestResult res = Backtester.run(cfg, bars);
            return new ChampionPool.OosEvaluation(signals, res);
        };

        List<ChampionPool.Champion> selected = ChampionPool.selectDecorrelated(
                population, evaluator, valChampionPoolSize, valChampionDesiredCount, valChampionMaxCorrelation);

        for (ChampionPool.Champion c : selected) {
            out.add(new ChampionExport(c));
            state.output.println("Champion #" + c.rank + " (pop rank " + c.populationRank + "): "
                    + "fitness=" + c.standardizedFitness
                    + " nodes=" + c.totalNodes
                    + " Sharpe=" + c.result.sharpe
                    + " corrWithPrior=" + c.maxCorrelationWithPrior, log);
        }
        return out;
    }

    private void exportValidationArtifacts(EvolutionState state, GPIndividual gp, BacktestResult oos,
                                           Validator.BootstrapResult bootstrap,
                                           Validator.PermutationResult permutation,
                                           double psr, double dsr, double skew, double excessKurt,
                                           int trials, List<CrossSymbolResult> cross) {
        String prefix = exportPrefix == null || exportPrefix.isBlank()
                ? datasetSymbol() + "-" + targetColumnName
                : exportPrefix;
        Path path = exportDirectory.resolve(prefix + "-validation.json");

        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"symbol\": \"").append(datasetSymbol()).append("\",\n");
        b.append("  \"oos_bars\": ").append(oos.barCount).append(",\n");
        b.append("  \"observed_sharpe\": ").append(jsonNumber(oos.sharpe)).append(",\n");
        b.append("  \"observed_sortino\": ").append(jsonNumber(oos.sortino)).append(",\n");
        b.append("  \"return_skew\": ").append(jsonNumber(skew)).append(",\n");
        b.append("  \"return_excess_kurtosis\": ").append(jsonNumber(excessKurt)).append(",\n");
        b.append("  \"bootstrap\": {\n");
        b.append("    \"resamples\": ").append(bootstrap.resamples).append(",\n");
        b.append("    \"block_size\": ").append(valBootstrapBlock).append(",\n");
        b.append("    \"sharpe_p05\": ").append(jsonNumber(bootstrap.sharpeP05)).append(",\n");
        b.append("    \"sharpe_median\": ").append(jsonNumber(bootstrap.sharpeMedian)).append(",\n");
        b.append("    \"sharpe_p95\": ").append(jsonNumber(bootstrap.sharpeP95)).append("\n");
        b.append("  },\n");
        b.append("  \"permutation\": {\n");
        b.append("    \"resamples\": ").append(permutation.resamples).append(",\n");
        b.append("    \"p_value\": ").append(jsonNumber(permutation.pValue)).append("\n");
        b.append("  },\n");
        b.append("  \"probabilistic_sharpe\": ").append(jsonNumber(psr)).append(",\n");
        b.append("  \"deflated_sharpe\": ").append(jsonNumber(dsr)).append(",\n");
        b.append("  \"deflated_sharpe_trials\": ").append(trials).append(",\n");
        b.append("  \"cross_symbol\": [");
        if (cross.isEmpty()) {
            b.append("],\n");
        } else {
            b.append("\n");
            for (int i = 0; i < cross.size(); i++) {
                CrossSymbolResult c = cross.get(i);
                b.append("    {");
                b.append("\"dataset\": \"").append(escape(c.path)).append("\"");
                if (c.result != null) {
                    b.append(", \"bars\": ").append(c.result.barCount);
                    b.append(", \"trades\": ").append(c.result.trades);
                    b.append(", \"sharpe\": ").append(jsonNumber(c.result.sharpe));
                    b.append(", \"sortino\": ").append(jsonNumber(c.result.sortino));
                    b.append(", \"total_return_pct\": ").append(jsonNumber(c.result.totalReturnPct));
                    b.append(", \"max_drawdown_pct\": ").append(jsonNumber(c.result.maxDrawdownPct));
                } else {
                    b.append(", \"error\": true");
                }
                b.append("}");
                if (i < cross.size() - 1) b.append(',');
                b.append("\n");
            }
            b.append("  ],\n");
        }
        b.append("  \"node_count\": ").append(treeNodeCount(gp)).append("\n");
        b.append("}\n");

        try {
            Files.writeString(path, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            state.output.warning("Unable to export validation artifacts: " + e.getMessage());
        }
    }

    private void exportChampions(EvolutionState state, List<ChampionExport> champions) {
        String prefix = exportPrefix == null || exportPrefix.isBlank()
                ? datasetSymbol() + "-" + targetColumnName
                : exportPrefix;
        Path path = exportDirectory.resolve(prefix + "-champions.json");

        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"symbol\": \"").append(datasetSymbol()).append("\",\n");
        b.append("  \"max_correlation\": ").append(valChampionMaxCorrelation).append(",\n");
        b.append("  \"pool_size\": ").append(valChampionPoolSize).append(",\n");
        b.append("  \"desired_count\": ").append(valChampionDesiredCount).append(",\n");
        b.append("  \"selected\": ").append(champions.size()).append(",\n");
        b.append("  \"champions\": [");
        if (champions.isEmpty()) {
            b.append("]\n");
        } else {
            b.append("\n");
            for (int i = 0; i < champions.size(); i++) {
                ChampionExport c = champions.get(i);
                b.append("    {\n");
                b.append("      \"rank\": ").append(c.rank).append(",\n");
                b.append("      \"population_rank\": ").append(c.populationRank).append(",\n");
                b.append("      \"standardized_fitness\": ").append(jsonNumber(c.standardizedFitness)).append(",\n");
                b.append("      \"node_count\": ").append(c.totalNodes).append(",\n");
                b.append("      \"sharpe\": ").append(jsonNumber(c.sharpe)).append(",\n");
                b.append("      \"total_return_pct\": ").append(jsonNumber(c.totalReturnPct)).append(",\n");
                b.append("      \"max_drawdown_pct\": ").append(jsonNumber(c.maxDrawdownPct)).append(",\n");
                b.append("      \"trades\": ").append(c.trades).append(",\n");
                b.append("      \"max_correlation_with_prior\": ").append(jsonNumber(c.maxCorrelationWithPrior)).append(",\n");
                b.append("      \"trees\": [");
                for (int t = 0; t < c.treeExpressions.size(); t++) {
                    b.append("\n        \"").append(escape(c.treeExpressions.get(t))).append("\"");
                    if (t < c.treeExpressions.size() - 1) b.append(',');
                }
                b.append("\n      ]\n");
                b.append("    }");
                if (i < champions.size() - 1) b.append(',');
                b.append("\n");
            }
            b.append("  ]\n");
        }
        b.append("}\n");

        try {
            Files.writeString(path, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            state.output.warning("Unable to export champion pool: " + e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class CrossSymbolResult {
        final String path;
        final BacktestResult result;

        CrossSymbolResult(String path, BacktestResult result) {
            this.path = path;
            this.result = result;
        }
    }

    private static final class ChampionExport {
        final int rank;
        final int populationRank;
        final double standardizedFitness;
        final int totalNodes;
        final double sharpe;
        final double totalReturnPct;
        final double maxDrawdownPct;
        final int trades;
        final double maxCorrelationWithPrior;
        final List<String> treeExpressions;

        ChampionExport(ChampionPool.Champion c) {
            this.rank = c.rank;
            this.populationRank = c.populationRank;
            this.standardizedFitness = c.standardizedFitness;
            this.totalNodes = c.totalNodes;
            this.sharpe = c.result.sharpe;
            this.totalReturnPct = c.result.totalReturnPct;
            this.maxDrawdownPct = c.result.maxDrawdownPct;
            this.trades = c.result.trades;
            this.maxCorrelationWithPrior = c.maxCorrelationWithPrior;
            this.treeExpressions = c.treeExpressions;
        }
    }

    private void exportWalkForwardArtifacts(EvolutionState state, GPIndividual gp, BacktestResult result) {
        String prefix = exportPrefix == null || exportPrefix.isBlank()
                ? datasetSymbol() + "-" + targetColumnName
                : exportPrefix;
        Path summaryPath = exportDirectory.resolve(prefix + "-walkforward-summary.json");
        Path equityPath = exportDirectory.resolve(prefix + "-walkforward-equity.csv");
        Path signalsPath = exportDirectory.resolve(prefix + "-walkforward-signals.csv");

        try {
            Files.writeString(summaryPath, walkForwardSummaryJson(gp, result), StandardCharsets.UTF_8);
            Files.writeString(equityPath, walkForwardEquityCsv(result), StandardCharsets.UTF_8);
            Files.writeString(signalsPath, walkForwardSignalsCsv(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            state.output.warning("Unable to export walk-forward artifacts: " + e.getMessage());
        }
    }

    private String walkForwardSummaryJson(GPIndividual gp, BacktestResult r) {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"symbol\": \"").append(datasetSymbol()).append("\",\n");
        b.append("  \"window_training_rows\": ").append(walkForwardTrainingRows).append(",\n");
        b.append("  \"window_test_rows\": ").append(walkForwardTestRows).append(",\n");
        b.append("  \"window_step_rows\": ").append(walkForwardStepRows).append(",\n");
        b.append("  \"oos_bars\": ").append(r.barCount).append(",\n");
        b.append("  \"initial_capital\": ").append(r.initialCapital).append(",\n");
        b.append("  \"final_equity\": ").append(r.finalEquity).append(",\n");
        b.append("  \"total_return_pct\": ").append(r.totalReturnPct).append(",\n");
        b.append("  \"max_drawdown_pct\": ").append(r.maxDrawdownPct).append(",\n");
        b.append("  \"trades\": ").append(r.trades).append(",\n");
        b.append("  \"wins\": ").append(r.wins).append(",\n");
        b.append("  \"win_rate_pct\": ").append(r.winRatePct).append(",\n");
        b.append("  \"sharpe\": ").append(jsonNumber(r.sharpe)).append(",\n");
        b.append("  \"sortino\": ").append(jsonNumber(r.sortino)).append(",\n");
        b.append("  \"calmar\": ").append(jsonNumber(r.calmar)).append(",\n");
        b.append("  \"cagr\": ").append(jsonNumber(r.cagr)).append(",\n");
        b.append("  \"turnover\": ").append(r.turnover).append(",\n");
        b.append("  \"profit_factor\": ").append(r.profitFactor).append(",\n");
        b.append("  \"average_holding_bars\": ").append(r.averageHoldingBars).append(",\n");
        b.append("  \"node_count\": ").append(treeNodeCount(gp)).append("\n");
        b.append("}\n");
        return b.toString();
    }

    private String walkForwardEquityCsv(BacktestResult r) {
        StringBuilder b = new StringBuilder("execution_timestamp,equity\n");
        for (EquityPoint p : r.equityCurve) {
            b.append(p.executionTimestamp).append(',').append(p.equity).append('\n');
        }
        return b.toString();
    }

    private String walkForwardSignalsCsv(BacktestResult r) {
        StringBuilder b = new StringBuilder(
                "signal_timestamp,execution_timestamp,entry_price,signal,raw_return,gross_strategy_return,cost_rate,net_strategy_return,pnl,equity\n");
        for (BarRecord bar : r.bars) {
            b.append(bar.signalTimestamp).append(',')
                    .append(bar.executionTimestamp).append(',')
                    .append(bar.entryPrice).append(',')
                    .append(signalLabel(bar.signal)).append(',')
                    .append(bar.rawReturn).append(',')
                    .append(bar.grossStrategyReturn).append(',')
                    .append(bar.costRate).append(',')
                    .append(bar.netStrategyReturn).append(',')
                    .append(bar.pnl).append(',')
                    .append(bar.equity).append('\n');
        }
        return b.toString();
    }

    private String jsonNumber(double value) {
        if (Double.isNaN(value)) return "null";
        if (Double.isInfinite(value)) return value > 0 ? "\"+Infinity\"" : "\"-Infinity\"";
        return Double.toString(value);
    }

    @Override
    public void evaluate(EvolutionState state, Individual individual, int subPopulation, int threadNum) {
        if (individual.evaluated) return;

        GPIndividual gp = (GPIndividual) individual;
        List<BarSample> samples = buildTrainingBars(state, gp, threadNum);

        BacktestResult result = Backtester.run(backtestConfigForFitness(), samples);
        int nodeCount = treeNodeCount(gp);

        double score = composeFitnessScore(result, nodeCount);
        double standardized = Math.max(0.0, fitnessOffset - score);

        KozaFitness fitness = (KozaFitness) gp.fitness;
        fitness.setStandardizedFitness(state, standardized);
        fitness.hits = result.wins;
        gp.evaluated = true;
    }

    /**
     * Composite score (higher is better). Sharpe and Calmar are clipped to
     * realistic bounds — anything beyond is almost certainly statistical noise
     * from a tiny sample, and we don't want to reward those individuals.
     */
    protected double composeFitnessScore(BacktestResult result, int nodeCount) {
        double sharpe = clip(safeFinite(result.sharpe), -5.0, 5.0);
        double calmar = clip(safeFinite(result.calmar), -10.0, 10.0);
        double tradesShortfall = Math.max(0, minTrades - result.trades) / (double) Math.max(1, minTrades);
        return weightSharpe * sharpe
                + weightCalmar * calmar
                - weightTurnover * result.turnover
                - weightComplexity * nodeCount / 100.0
                - weightMinTrades * tradesShortfall;
    }

    private static double clip(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** Build BarSamples for fitness — walk-forward stitched OOS or holdout-training range. */
    protected List<BarSample> buildTrainingBars(EvolutionState state, GPIndividual gp, int threadNum) {
        if (validationMode == ValidationMode.WALK_FORWARD) {
            return buildWalkForwardOosBars(state, gp, threadNum);
        }
        return buildBarsForRange(state, gp, threadNum, 0, trainingEndIndex - 1);
    }

    protected List<BarSample> buildWalkForwardOosBars(EvolutionState state, GPIndividual gp, int threadNum) {
        List<BarSample> stitched = new ArrayList<>();
        int windowStart = 0;
        int lastTrainingRow = walkForwardTrainingRows - 1;
        while (lastTrainingRow + walkForwardTestRows < trainingEndIndex) {
            int oosStart = lastTrainingRow + 1;
            int oosEndExclusive = Math.min(oosStart + walkForwardTestRows, trainingEndIndex);
            List<BarSample> windowBars = buildBarsForRange(state, gp, threadNum, oosStart, oosEndExclusive - 1);
            stitched.addAll(windowBars);
            windowStart += walkForwardStepRows;
            lastTrainingRow = windowStart + walkForwardTrainingRows - 1;
        }
        return stitched;
    }

    protected List<BarSample> buildBarsForRange(EvolutionState state, GPIndividual gp, int threadNum, int startRow, int endExclusive) {
        DoubleData input = (DoubleData) this.input;
        List<BarSample> bars = new ArrayList<>();
        int start = Math.max(0, startRow);
        int end = Math.min(endExclusive, stockData.length - 1);
        for (int row = start; row < end; row++) {
            if (!loadRowContext(stockData, row)) continue;

            double entryPrice = stockData[row][backtestEntryColumnIndex];
            if (entryPrice <= 0.0) continue;

            gp.trees[0].child.eval(state, threadNum, input, this.stack, gp, this);
            double alpha = input.x;
            if (!Double.isFinite(alpha)) continue;

            int signal = signalSide(alpha);
            double rawReturn = realizedTradeReturn(row, entryPrice);
            bars.add(new BarSample(
                    stockTimestamps[row],
                    row + 1 < stockTimestamps.length ? stockTimestamps[row + 1] : stockTimestamps[row],
                    signal,
                    rawReturn,
                    entryPrice));
        }
        return bars;
    }

    protected double realizedTradeReturn(int row, double entryPrice) {
        if (row + 1 >= stockData.length) return 0.0;
        double exit = stockData[row + 1][targetColumnIndex];
        if (targetMode == TargetMode.RETURN) {
            double current = stockData[row][targetColumnIndex];
            return (exit - current) / Math.max(Math.abs(current), 1.0e-12);
        }
        return (exit - entryPrice) / Math.max(Math.abs(entryPrice), 1.0e-12);
    }

    private static double safeFinite(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (Double.isInfinite(v)) return v > 0 ? 1.0e6 : -1.0e6;
        return v;
    }

    private static ExecutionMode parseExecutionMode(String value, EvolutionState state, Parameter parameter) {
        if (value == null) return ExecutionMode.ALWAYS_FLAT_OVERNIGHT;
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "always-flat-overnight", "always_flat_overnight", "flat" -> ExecutionMode.ALWAYS_FLAT_OVERNIGHT;
            case "position-aware", "position_aware", "hold" -> ExecutionMode.POSITION_AWARE;
            default -> {
                state.output.fatal("Unsupported fitness.execution-mode: " + value, parameter, null);
                yield ExecutionMode.ALWAYS_FLAT_OVERNIGHT;
            }
        };
    }
}
