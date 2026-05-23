package StockPredictor;

import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.gp.GPProblem;
import ec.gp.koza.KozaFitness;
import ec.simple.SimpleProblemForm;
import ec.util.Parameter;
import backtest.BacktestConfig;
import backtest.BacktestResult;
import backtest.Backtester;
import backtest.BarRecord;
import backtest.BarSample;
import backtest.EquityPoint;
import backtest.ExecutionMode;
import feature.FeaturePipeline;
import feature.FeatureSeries;
import feature.RowContext;
import terminal.DoubleData;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class Stock extends GPProblem implements SimpleProblemForm {
    public static final String P_DATA = "data";
    public static final String P_DATASET = "dataset";
    public static final String P_TRAINING_SPLIT = "training-split";
    public static final String P_TARGET_COLUMN = "target-column";
    public static final String P_HIT_THRESHOLD = "hit-threshold";
    public static final String P_VALIDATION_MODE = "validation-mode";
    public static final String P_WALK_FORWARD_TRAINING_ROWS = "walk-forward-training-rows";
    public static final String P_WALK_FORWARD_TEST_ROWS = "walk-forward-test-rows";
    public static final String P_WALK_FORWARD_STEP_ROWS = "walk-forward-step-rows";
    public static final String P_COMPLEXITY_PENALTY = "complexity-penalty";
    public static final String P_COMPLEXITY_FREE_NODES = "complexity-free-nodes";
    public static final String P_EXPORT_DIR = "export-dir";
    public static final String P_EXPORT_PREFIX = "export-prefix";
    public static final String P_BACKTEST_INITIAL_CAPITAL = "backtest.initial-capital";
    public static final String P_BACKTEST_POSITION_FRACTION = "backtest.position-fraction";
    public static final String P_BACKTEST_COMMISSION_BPS = "backtest.commission-bps";
    public static final String P_BACKTEST_SLIPPAGE_BPS = "backtest.slippage-bps";
    public static final String P_BACKTEST_SIGNAL_THRESHOLD = "backtest.signal-threshold";
    public static final String P_BACKTEST_ENTRY_COLUMN = "backtest.entry-column";

    private static final double PROBABLY_ZERO = 1.11E-15;
    private static final double BIG_NUMBER = 1.0E15;
    private static final int CSV_COLUMN_COUNT = 7;
    private static final int OPEN_INDEX = 0;
    private static final int HIGH_INDEX = 1;
    private static final int LOW_INDEX = 2;
    private static final int CLOSE_INDEX = 3;
    private static final int ADJUSTED_CLOSE_INDEX = 4;
    private static final int VOLUME_INDEX = 5;
    private static final int MIN_ROWS_FOR_FEATURES = 50;
    private static final String DEFAULT_DATASET = "src/main/data/MSFT.csv";
    private static final double DEFAULT_TRAINING_SPLIT = 0.8;
    private static final String DEFAULT_TARGET_COLUMN = "open";
    private static final double DEFAULT_HIT_THRESHOLD = 0.005;
    private static final String DEFAULT_VALIDATION_MODE = "holdout";
    private static final int DEFAULT_WALK_FORWARD_TRAINING_ROWS = 252;
    private static final int DEFAULT_WALK_FORWARD_TEST_ROWS = 20;
    private static final int DEFAULT_WALK_FORWARD_STEP_ROWS = 20;
    private static final double DEFAULT_COMPLEXITY_PENALTY = 0.0;
    private static final int DEFAULT_COMPLEXITY_FREE_NODES = 60;
    private static final String DEFAULT_EXPORT_DIR = "src/main/results";
    private static final double DEFAULT_BACKTEST_INITIAL_CAPITAL = 100_000.0;
    private static final double DEFAULT_BACKTEST_POSITION_FRACTION = 1.0;
    private static final double DEFAULT_BACKTEST_COMMISSION_BPS = 1.0;
    private static final double DEFAULT_BACKTEST_SLIPPAGE_BPS = 2.0;
    private static final double DEFAULT_BACKTEST_SIGNAL_THRESHOLD = 0.002;
    private static final String DEFAULT_BACKTEST_ENTRY_COLUMN = "close";

    public double open, high, low, close, adjustedClose, volume, movingTenDayAvg, movingFiftyDayAvg;
    public double previousOpen, previousClose, dailyRange, intradayChange, volumeTenDayAvg;
    public double laggedOpenReturn, laggedCloseReturn, closeVolatilityTenDay, closeVolatilityTwentyDay;

    public RowContext activeRow;

    protected double[][] stockData = new double[0][0];
    protected String[] stockTimestamps = new String[0];
    protected FeatureSeries featureSeries;
    protected String datasetPath = DEFAULT_DATASET;
    protected int targetColumnIndex = OPEN_INDEX;
    protected String targetColumnName = DEFAULT_TARGET_COLUMN;
    protected TargetMode targetMode = TargetMode.PRICE;
    protected double acceptedError = DEFAULT_HIT_THRESHOLD;
    protected ValidationMode validationMode = ValidationMode.HOLDOUT;
    protected int walkForwardTrainingRows = DEFAULT_WALK_FORWARD_TRAINING_ROWS;
    protected int walkForwardTestRows = DEFAULT_WALK_FORWARD_TEST_ROWS;
    protected int walkForwardStepRows = DEFAULT_WALK_FORWARD_STEP_ROWS;
    protected int trainingEndIndex = 0;
    protected int testingStartIndex = 0;
    protected double complexityPenaltyFactor = DEFAULT_COMPLEXITY_PENALTY;
    protected int complexityFreeNodes = DEFAULT_COMPLEXITY_FREE_NODES;
    protected Path exportDirectory = Path.of(DEFAULT_EXPORT_DIR).toAbsolutePath().normalize();
    protected String exportPrefix = "";
    protected int backtestEntryColumnIndex = CLOSE_INDEX;
    protected String backtestEntryColumnName = DEFAULT_BACKTEST_ENTRY_COLUMN;
    protected double backtestInitialCapital = DEFAULT_BACKTEST_INITIAL_CAPITAL;
    protected double backtestPositionFraction = DEFAULT_BACKTEST_POSITION_FRACTION;
    protected double backtestCommissionBps = DEFAULT_BACKTEST_COMMISSION_BPS;
    protected double backtestSlippageBps = DEFAULT_BACKTEST_SLIPPAGE_BPS;
    protected double backtestSignalThreshold = DEFAULT_BACKTEST_SIGNAL_THRESHOLD;

    @Override
    public void evaluate(EvolutionState evolutionState, Individual individual, int subPopulation, int threadNum) {
        if (!individual.evaluated) {
            GPIndividual gpIndividual = (GPIndividual) individual;
            EvaluationSummary summary = evaluateTrainingFitness(evolutionState, gpIndividual, threadNum);
            int nodeCount = treeNodeCount(gpIndividual);
            double standardizedFitness = summary.averageNormalizedError + complexityPenalty(nodeCount);
            KozaFitness kozaFitness = ((KozaFitness) individual.fitness);
            kozaFitness.setStandardizedFitness(evolutionState, standardizedFitness);
            kozaFitness.hits = summary.hits;
            individual.evaluated = true;
        }
    }

    @Override
    public void describe(EvolutionState state, Individual bestIndividual, int subpopulation, int threadnum, int log) {
        super.describe(state, bestIndividual, subpopulation, threadnum, log);

        if (!(bestIndividual instanceof GPIndividual)) {
            state.output.fatal("The best individual is not an instance of GPIndividual!!");
        }
        GPIndividual gpIndividual = (GPIndividual) bestIndividual;

        EvaluationSummary testingSummary = evaluateTestingFitness(state, gpIndividual, threadnum);
        BacktestArtifacts backtest = backtestTestingRange(state, gpIndividual, threadnum);
        BacktestResult result = backtest.result;
        LiveSignal liveSignal = generateLiveSignal(state, gpIndividual, threadnum);
        int nodeCount = treeNodeCount(gpIndividual);

        state.output.println("Dataset: " + datasetPath, log);
        state.output.println("Prediction target: " + targetColumnName, log);
        state.output.println("Best Individual node count: " + nodeCount, log);
        state.output.println("Best Individual complexity penalty: " + complexityPenalty(nodeCount), log);
        state.output.println("Best Individual's testing hits: " + testingSummary.hits + " out of " + testingSummary.evaluatedCases, log);
        state.output.println("Best Individual's testing accuracy: " + percentage(testingSummary.hits, testingSummary.evaluatedCases) + "%", log);
        state.output.println("Best Individual's directional accuracy: " + percentage(testingSummary.directionalHits, testingSummary.evaluatedCases) + "%", log);
        state.output.println("Best Individual's average normalized error: " + testingSummary.averageNormalizedError, log);
        state.output.println("Backtest trades: " + result.trades, log);
        state.output.println("Backtest win rate: " + result.winRatePct + "%", log);
        state.output.println("Backtest total return: " + result.totalReturnPct + "%", log);
        state.output.println("Backtest max drawdown: " + result.maxDrawdownPct + "%", log);
        state.output.println("Backtest final equity: " + result.finalEquity, log);
        state.output.println("Backtest Sharpe (annualized): " + result.sharpe, log);
        state.output.println("Backtest Sortino (annualized): " + result.sortino, log);
        state.output.println("Backtest Calmar: " + result.calmar, log);
        state.output.println("Latest live signal: " + liveSignal.signal, log);
        state.output.println("Latest predicted trade return: " + liveSignal.predictedTradeReturn, log);

        exportArtifacts(state, gpIndividual, testingSummary, backtest, liveSignal);
    }

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);
        if (!(input instanceof DoubleData)) {
            state.output.fatal("GPData class must subclass from " + DoubleData.class, base.push(P_DATA), null);
        }

        datasetPath = state.parameters.getString(base.push(P_DATASET), null);
        if (datasetPath == null || datasetPath.isBlank()) {
            datasetPath = DEFAULT_DATASET;
        }

        double trainingSplit = state.parameters.getDoubleWithDefault(base.push(P_TRAINING_SPLIT), null, DEFAULT_TRAINING_SPLIT);
        if (trainingSplit <= 0.0 || trainingSplit >= 1.0) {
            state.output.fatal("training-split must be between 0 and 1 (exclusive).", base.push(P_TRAINING_SPLIT), null);
        }

        String configuredTargetColumn = state.parameters.getStringWithDefault(base.push(P_TARGET_COLUMN), null, DEFAULT_TARGET_COLUMN);
        TargetSpec targetSpec = resolveTargetSpec(configuredTargetColumn, state, base.push(P_TARGET_COLUMN));
        targetColumnIndex = targetSpec.columnIndex;
        targetColumnName = targetSpec.name;
        targetMode = targetSpec.mode;

        acceptedError = state.parameters.getDoubleWithDefault(base.push(P_HIT_THRESHOLD), null, DEFAULT_HIT_THRESHOLD);
        if (acceptedError <= 0.0) {
            state.output.fatal("hit-threshold must be greater than 0.", base.push(P_HIT_THRESHOLD), null);
        }

        String configuredValidationMode = state.parameters.getStringWithDefault(base.push(P_VALIDATION_MODE), null, DEFAULT_VALIDATION_MODE);
        validationMode = resolveValidationMode(configuredValidationMode, state, base.push(P_VALIDATION_MODE));
        walkForwardTrainingRows = state.parameters.getIntWithDefault(base.push(P_WALK_FORWARD_TRAINING_ROWS), null, DEFAULT_WALK_FORWARD_TRAINING_ROWS);
        walkForwardTestRows = state.parameters.getIntWithDefault(base.push(P_WALK_FORWARD_TEST_ROWS), null, DEFAULT_WALK_FORWARD_TEST_ROWS);
        walkForwardStepRows = state.parameters.getIntWithDefault(base.push(P_WALK_FORWARD_STEP_ROWS), null, DEFAULT_WALK_FORWARD_STEP_ROWS);
        if (walkForwardTrainingRows < MIN_ROWS_FOR_FEATURES) {
            state.output.fatal("walk-forward-training-rows must be at least " + MIN_ROWS_FOR_FEATURES + ".", base.push(P_WALK_FORWARD_TRAINING_ROWS), null);
        }
        if (walkForwardTestRows < 1) {
            state.output.fatal("walk-forward-test-rows must be at least 1.", base.push(P_WALK_FORWARD_TEST_ROWS), null);
        }
        if (walkForwardStepRows < 1) {
            state.output.fatal("walk-forward-step-rows must be at least 1.", base.push(P_WALK_FORWARD_STEP_ROWS), null);
        }

        complexityPenaltyFactor = state.parameters.getDoubleWithDefault(base.push(P_COMPLEXITY_PENALTY), null, DEFAULT_COMPLEXITY_PENALTY);
        complexityFreeNodes = state.parameters.getIntWithDefault(base.push(P_COMPLEXITY_FREE_NODES), null, DEFAULT_COMPLEXITY_FREE_NODES);
        if (complexityPenaltyFactor < 0.0) {
            state.output.fatal("complexity-penalty must be >= 0.", base.push(P_COMPLEXITY_PENALTY), null);
        }
        if (complexityFreeNodes < 1) {
            state.output.fatal("complexity-free-nodes must be >= 1.", base.push(P_COMPLEXITY_FREE_NODES), null);
        }

        String configuredExportDir = state.parameters.getStringWithDefault(base.push(P_EXPORT_DIR), null, DEFAULT_EXPORT_DIR);
        exportDirectory = Path.of(configuredExportDir).toAbsolutePath().normalize();
        exportPrefix = state.parameters.getStringWithDefault(base.push(P_EXPORT_PREFIX), null, "");

        TradingColumnSpec backtestEntryColumn = resolvePriceColumn(
                state.parameters.getStringWithDefault(base.push(P_BACKTEST_ENTRY_COLUMN), null, DEFAULT_BACKTEST_ENTRY_COLUMN),
                state,
                base.push(P_BACKTEST_ENTRY_COLUMN)
        );
        backtestEntryColumnIndex = backtestEntryColumn.columnIndex;
        backtestEntryColumnName = backtestEntryColumn.name;
        backtestInitialCapital = state.parameters.getDoubleWithDefault(base.push(P_BACKTEST_INITIAL_CAPITAL), null, DEFAULT_BACKTEST_INITIAL_CAPITAL);
        backtestPositionFraction = state.parameters.getDoubleWithDefault(base.push(P_BACKTEST_POSITION_FRACTION), null, DEFAULT_BACKTEST_POSITION_FRACTION);
        backtestCommissionBps = state.parameters.getDoubleWithDefault(base.push(P_BACKTEST_COMMISSION_BPS), null, DEFAULT_BACKTEST_COMMISSION_BPS);
        backtestSlippageBps = state.parameters.getDoubleWithDefault(base.push(P_BACKTEST_SLIPPAGE_BPS), null, DEFAULT_BACKTEST_SLIPPAGE_BPS);
        backtestSignalThreshold = state.parameters.getDoubleWithDefault(base.push(P_BACKTEST_SIGNAL_THRESHOLD), null, DEFAULT_BACKTEST_SIGNAL_THRESHOLD);
        validateBacktestConfiguration(state, base);

        ParsedDataset parsedDataset = readCsv(datasetPath, state);
        stockData = parsedDataset.values;
        stockTimestamps = parsedDataset.timestamps;
        featureSeries = FeaturePipeline.compute(stockData, stockTimestamps);
        splitData(trainingSplit, state);
        ensureExportDirectory(state, base.push(P_EXPORT_DIR));
    }

    public FeatureSeries featureSeries() {
        return featureSeries;
    }

    public void splitData(double percentage, EvolutionState state) {
        int trainingRows = (int) Math.round(stockData.length * percentage);
        trainingRows = Math.max(1, Math.min(trainingRows, stockData.length - 1));

        trainingEndIndex = trainingRows;
        testingStartIndex = trainingRows;

        int testingRows = stockData.length - trainingRows;
        if (trainingRows <= MIN_ROWS_FOR_FEATURES || testingRows <= 1) {
            state.output.warning("One of the dataset splits has too few rows for stable evaluation.");
        }

        state.output.message("Loaded dataset: " + datasetPath);
        state.output.message("Training rows: " + trainingRows);
        state.output.message("Testing rows: " + testingRows);
        state.output.message("Prediction target: " + targetColumnName);
        state.output.message("Hit threshold: " + acceptedError);
        state.output.message("Validation mode: " + validationMode.name().toLowerCase(Locale.ROOT));
        state.output.message("Complexity penalty: " + complexityPenaltyFactor);
        state.output.message("Complexity free nodes: " + complexityFreeNodes);
        state.output.message("Backtest entry column: " + backtestEntryColumnName);
        if (validationMode == ValidationMode.WALK_FORWARD) {
            if (trainingEndIndex <= walkForwardTrainingRows + walkForwardTestRows) {
                state.output.fatal("Training split is too small for the configured walk-forward window sizes.");
            }
            state.output.message("Walk-forward training rows: " + walkForwardTrainingRows);
            state.output.message("Walk-forward test rows: " + walkForwardTestRows);
            state.output.message("Walk-forward step rows: " + walkForwardStepRows);
        }
    }

    public ParsedDataset readCsv(String path, EvolutionState state) {
        List<String> timestamps = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            boolean isHeader = true;
            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length < CSV_COLUMN_COUNT) {
                    state.output.warning("Skipping malformed row in dataset: " + line);
                    continue;
                }

                try {
                    timestamps.add(parts[0]);
                    rows.add(parseNumericColumns(parts));
                } catch (NumberFormatException e) {
                    state.output.warning("Skipping non-numeric row in dataset: " + line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading file", e);
        }

        if (rows.size() < 2) {
            state.output.fatal("Dataset must contain at least two data rows: " + path);
        }

        return new ParsedDataset(rows.toArray(new double[0][]), timestamps.toArray(new String[0]));
    }

    private EvaluationSummary evaluateTrainingFitness(EvolutionState state, GPIndividual individual, int threadNum) {
        if (validationMode == ValidationMode.WALK_FORWARD) {
            return evaluateWalkForward(state, individual, threadNum);
        }
        return evaluateDatasetRange(state, individual, threadNum, 0, trainingEndIndex - 1);
    }

    private EvaluationSummary evaluateTestingFitness(EvolutionState state, GPIndividual individual, int threadNum) {
        return evaluateDatasetRange(state, individual, threadNum, testingStartIndex, stockData.length - 1);
    }

    private EvaluationSummary evaluateWalkForward(EvolutionState state, GPIndividual individual, int threadNum) {
        EvaluationSummary aggregate = new EvaluationSummary(0.0, 0, 0);
        int windowStart = 0;
        int lastTrainingRow = walkForwardTrainingRows - 1;

        while (lastTrainingRow + walkForwardTestRows < trainingEndIndex) {
            int evaluationStart = lastTrainingRow;
            int evaluationEndExclusive = Math.min(evaluationStart + walkForwardTestRows, trainingEndIndex - 1);
            EvaluationSummary windowSummary = evaluateDatasetRange(state, individual, threadNum, evaluationStart, evaluationEndExclusive);
            aggregate = aggregate.combine(windowSummary);

            windowStart += walkForwardStepRows;
            lastTrainingRow = windowStart + walkForwardTrainingRows - 1;
        }

        if (aggregate.evaluatedCases == 0) {
            return new EvaluationSummary(BIG_NUMBER, 0, 0);
        }

        return new EvaluationSummary(aggregate.totalNormalizedError / aggregate.evaluatedCases, aggregate.hits, aggregate.evaluatedCases);
    }

    private EvaluationSummary evaluateDatasetRange(EvolutionState state, GPIndividual individual, int threadNum, int startRow, int endExclusive) {
        DoubleData input = (DoubleData) this.input;
        double totalNormalizedError = 0.0;
        int hits = 0;
        int directionalHits = 0;
        int evaluatedCases = 0;

        for (int i = Math.max(0, startRow); i < Math.min(endExclusive, stockData.length - 1); i++) {
            if (!loadRowContext(stockData, i)) {
                continue;
            }

            double currentValue = stockData[i][targetColumnIndex];
            double expectedResult = expectedTargetValue(stockData, i);
            individual.trees[0].child.eval(state, threadNum, input, this.stack, individual, this);
            double predictedValue = input.x;

            double normalizedError = normalizedError(expectedResult, predictedValue);
            if (normalizedError <= acceptedError) {
                hits++;
            }
            if (sameDirection(expectedResult, predictedValue, currentValue)) {
                directionalHits++;
            }

            totalNormalizedError += normalizedError;
            evaluatedCases++;
        }

        if (evaluatedCases == 0) {
            return new EvaluationSummary(BIG_NUMBER, 0, 0);
        }

        return new EvaluationSummary(totalNormalizedError / evaluatedCases, hits, directionalHits, evaluatedCases, totalNormalizedError);
    }

    private BacktestArtifacts backtestTestingRange(EvolutionState state, GPIndividual individual, int threadNum) {
        DoubleData input = (DoubleData) this.input;

        List<BarSample> samples = new ArrayList<>();
        List<Double> predictedValues = new ArrayList<>();
        List<Double> predictedTradeReturns = new ArrayList<>();
        List<Double> actualTradeReturns = new ArrayList<>();
        List<Double> actualTargetValues = new ArrayList<>();

        for (int row = testingStartIndex; row < stockData.length - 1; row++) {
            if (!loadRowContext(stockData, row)) {
                continue;
            }

            double entryPrice = stockData[row][backtestEntryColumnIndex];
            if (entryPrice <= PROBABLY_ZERO) {
                continue;
            }

            individual.trees[0].child.eval(state, threadNum, input, this.stack, individual, this);
            double predictedValue = input.x;
            double predictedTradeReturn = predictedTradeReturn(predictedValue, entryPrice);
            double actualTradeReturn = actualTradeReturn(stockData, row, entryPrice);
            int signalSide = signalSide(predictedTradeReturn);

            samples.add(new BarSample(
                    stockTimestamps[row],
                    stockTimestamps[row + 1],
                    signalSide,
                    actualTradeReturn,
                    entryPrice
            ));
            predictedValues.add(predictedValue);
            predictedTradeReturns.add(predictedTradeReturn);
            actualTradeReturns.add(actualTradeReturn);
            actualTargetValues.add(targetMode == TargetMode.PRICE ? stockData[row + 1][targetColumnIndex] : Double.NaN);
        }

        BacktestConfig config = BacktestConfig.builder()
                .initialCapital(backtestInitialCapital)
                .positionFraction(backtestPositionFraction)
                .commissionBps(backtestCommissionBps)
                .slippageBps(backtestSlippageBps)
                .barsPerYear(252)
                .mode(ExecutionMode.ALWAYS_FLAT_OVERNIGHT)
                .build();

        BacktestResult result = Backtester.run(config, samples);
        return new BacktestArtifacts(result, predictedValues, predictedTradeReturns, actualTradeReturns, actualTargetValues);
    }

    private LiveSignal generateLiveSignal(EvolutionState state, GPIndividual individual, int threadNum) {
        DoubleData input = (DoubleData) this.input;
        int row = stockData.length - 1;
        if (row < 0 || !loadRowContext(stockData, row)) {
            return new LiveSignal(datasetSymbol(), "", "FLAT", Double.NaN, Double.NaN, backtestEntryColumnName, targetColumnName);
        }

        double entryPrice = stockData[row][backtestEntryColumnIndex];
        individual.trees[0].child.eval(state, threadNum, input, this.stack, individual, this);
        double predictedValue = input.x;
        double predictedTradeReturn = predictedTradeReturn(predictedValue, entryPrice);
        String signal = signalLabel(signalSide(predictedTradeReturn));

        return new LiveSignal(
                datasetSymbol(),
                stockTimestamps[row],
                signal,
                predictedValue,
                predictedTradeReturn,
                backtestEntryColumnName,
                targetColumnName
        );
    }

    private void exportArtifacts(EvolutionState state, GPIndividual individual, EvaluationSummary testingSummary, BacktestArtifacts backtest, LiveSignal liveSignal) {
        String prefix = exportPrefix == null || exportPrefix.isBlank()
                ? datasetSymbol() + "-" + targetColumnName
                : exportPrefix;

        int nodeCount = treeNodeCount(individual);
        Path summaryPath = exportDirectory.resolve(prefix + "-summary.json");
        Path signalsPath = exportDirectory.resolve(prefix + "-signals.csv");
        Path equityPath = exportDirectory.resolve(prefix + "-equity.csv");
        Path latestSignalPath = exportDirectory.resolve(prefix + "-latest-signal.json");
        Path tradingViewWebhookPath = exportDirectory.resolve(prefix + "-tradingview-webhook.json");

        try {
            Files.writeString(summaryPath, summaryJson(individual, testingSummary, backtest, liveSignal, nodeCount), StandardCharsets.UTF_8);
            Files.writeString(signalsPath, signalsCsv(backtest), StandardCharsets.UTF_8);
            Files.writeString(equityPath, equityCsv(backtest), StandardCharsets.UTF_8);
            Files.writeString(latestSignalPath, latestSignalJson(liveSignal), StandardCharsets.UTF_8);
            Files.writeString(tradingViewWebhookPath, tradingViewWebhookJson(liveSignal), StandardCharsets.UTF_8);
        } catch (IOException e) {
            state.output.warning("Unable to export trading artifacts: " + e.getMessage());
        }
    }

    private void ensureExportDirectory(EvolutionState state, Parameter parameter) {
        try {
            Files.createDirectories(exportDirectory);
        } catch (IOException e) {
            state.output.fatal("Unable to create export directory: " + exportDirectory, parameter, null);
        }
    }

    protected boolean loadRowContext(double[][] data, int row) {
        RowContext ctx = featureSeries.contextAt(row);
        activeRow = ctx;
        open = ctx.open;
        high = ctx.high;
        low = ctx.low;
        close = ctx.close;
        adjustedClose = ctx.adjustedClose;
        volume = ctx.volume;
        previousOpen = ctx.previousOpen;
        previousClose = ctx.previousClose;
        dailyRange = ctx.dailyRange;
        intradayChange = ctx.intradayChange;
        laggedOpenReturn = ctx.laggedOpenReturn;
        laggedCloseReturn = ctx.laggedCloseReturn;
        movingTenDayAvg = ctx.movingTenDayAvg;
        movingFiftyDayAvg = ctx.movingFiftyDayAvg;
        volumeTenDayAvg = ctx.volumeTenDayAvg;
        closeVolatilityTenDay = ctx.closeVolatilityTenDay;
        closeVolatilityTwentyDay = ctx.closeVolatilityTwentyDay;
        return ctx.isComplete();
    }

    private double[] parseNumericColumns(String[] parts) {
        double[] row = new double[CSV_COLUMN_COUNT - 1];
        row[OPEN_INDEX] = Double.parseDouble(parts[1]);
        row[HIGH_INDEX] = Double.parseDouble(parts[2]);
        row[LOW_INDEX] = Double.parseDouble(parts[3]);
        row[CLOSE_INDEX] = Double.parseDouble(parts[4]);
        row[ADJUSTED_CLOSE_INDEX] = Double.parseDouble(parts[5]);
        row[VOLUME_INDEX] = Double.parseDouble(parts[6]);
        return row;
    }

    private double normalizedError(double expected, double predicted) {
        double error;
        if (targetMode == TargetMode.RETURN) {
            error = Math.abs(expected - predicted);
        } else {
            double denominator = Math.max(Math.abs(expected), PROBABLY_ZERO);
            error = Math.abs(expected - predicted) / denominator;
        }
        if (!Double.isFinite(error) || error > BIG_NUMBER) {
            return BIG_NUMBER;
        }
        if (error < PROBABLY_ZERO) {
            return 0.0;
        }
        return error;
    }

    private double percentage(double numerator, double denominator) {
        if (Math.abs(denominator) < PROBABLY_ZERO) {
            return 0.0;
        }
        return (numerator * 100.0) / denominator;
    }

    private TargetSpec resolveTargetSpec(String configuredTargetColumn, EvolutionState state, Parameter parameter) {
        return switch (configuredTargetColumn.toLowerCase(Locale.ROOT)) {
            case "open" -> new TargetSpec(OPEN_INDEX, "open", TargetMode.PRICE);
            case "high" -> new TargetSpec(HIGH_INDEX, "high", TargetMode.PRICE);
            case "low" -> new TargetSpec(LOW_INDEX, "low", TargetMode.PRICE);
            case "close" -> new TargetSpec(CLOSE_INDEX, "close", TargetMode.PRICE);
            case "adjusted-close", "adjusted_close", "adj-close", "adj_close" ->
                    new TargetSpec(ADJUSTED_CLOSE_INDEX, "adjusted-close", TargetMode.PRICE);
            case "volume" -> new TargetSpec(VOLUME_INDEX, "volume", TargetMode.PRICE);
            case "open-return", "open_return", "return-open", "return_open" ->
                    new TargetSpec(OPEN_INDEX, "open-return", TargetMode.RETURN);
            case "close-return", "close_return", "return-close", "return_close" ->
                    new TargetSpec(CLOSE_INDEX, "close-return", TargetMode.RETURN);
            case "adjusted-close-return", "adjusted_close_return", "adj-close-return", "adj_close_return" ->
                    new TargetSpec(ADJUSTED_CLOSE_INDEX, "adjusted-close-return", TargetMode.RETURN);
            default -> {
                state.output.fatal("Unsupported target-column: " + configuredTargetColumn, parameter, null);
                yield new TargetSpec(OPEN_INDEX, "open", TargetMode.PRICE);
            }
        };
    }

    private TradingColumnSpec resolvePriceColumn(String configuredColumn, EvolutionState state, Parameter parameter) {
        return switch (configuredColumn.toLowerCase(Locale.ROOT)) {
            case "open" -> new TradingColumnSpec(OPEN_INDEX, "open");
            case "high" -> new TradingColumnSpec(HIGH_INDEX, "high");
            case "low" -> new TradingColumnSpec(LOW_INDEX, "low");
            case "close" -> new TradingColumnSpec(CLOSE_INDEX, "close");
            case "adjusted-close", "adjusted_close", "adj-close", "adj_close" ->
                    new TradingColumnSpec(ADJUSTED_CLOSE_INDEX, "adjusted-close");
            default -> {
                state.output.fatal("Unsupported backtest.entry-column: " + configuredColumn, parameter, null);
                yield new TradingColumnSpec(CLOSE_INDEX, "close");
            }
        };
    }

    private void validateBacktestConfiguration(EvolutionState state, Parameter base) {
        if (backtestInitialCapital <= 0.0) {
            state.output.fatal("backtest.initial-capital must be > 0.", base.push(P_BACKTEST_INITIAL_CAPITAL), null);
        }
        if (backtestPositionFraction <= 0.0 || backtestPositionFraction > 1.0) {
            state.output.fatal("backtest.position-fraction must be in (0, 1].", base.push(P_BACKTEST_POSITION_FRACTION), null);
        }
        if (backtestCommissionBps < 0.0) {
            state.output.fatal("backtest.commission-bps must be >= 0.", base.push(P_BACKTEST_COMMISSION_BPS), null);
        }
        if (backtestSlippageBps < 0.0) {
            state.output.fatal("backtest.slippage-bps must be >= 0.", base.push(P_BACKTEST_SLIPPAGE_BPS), null);
        }
        if (backtestSignalThreshold < 0.0) {
            state.output.fatal("backtest.signal-threshold must be >= 0.", base.push(P_BACKTEST_SIGNAL_THRESHOLD), null);
        }
        if (targetColumnIndex == VOLUME_INDEX) {
            state.output.fatal("Backtesting is only supported for price and return targets, not volume.", base.push(P_TARGET_COLUMN), null);
        }
        if (targetMode == TargetMode.RETURN && backtestEntryColumnIndex != targetColumnIndex) {
            state.output.fatal(
                    "For return targets, backtest.entry-column must match the target price column.",
                    base.push(P_BACKTEST_ENTRY_COLUMN),
                    null
            );
        }
    }

    private double expectedTargetValue(double[][] data, int row) {
        if (targetMode == TargetMode.RETURN) {
            double current = data[row][targetColumnIndex];
            double next = data[row + 1][targetColumnIndex];
            return (next - current) / Math.max(Math.abs(current), PROBABLY_ZERO);
        }
        return data[row + 1][targetColumnIndex];
    }

    private boolean sameDirection(double expected, double predicted, double currentValue) {
        if (targetMode == TargetMode.RETURN) {
            return direction(expected) == direction(predicted);
        }
        return direction(expected - currentValue) == direction(predicted - currentValue);
    }

    private int direction(double value) {
        if (value > PROBABLY_ZERO) {
            return 1;
        }
        if (value < -PROBABLY_ZERO) {
            return -1;
        }
        return 0;
    }

    private ValidationMode resolveValidationMode(String configuredValidationMode, EvolutionState state, Parameter parameter) {
        return switch (configuredValidationMode.toLowerCase(Locale.ROOT)) {
            case "holdout" -> ValidationMode.HOLDOUT;
            case "walk-forward", "walk_forward" -> ValidationMode.WALK_FORWARD;
            default -> {
                state.output.fatal("Unsupported validation-mode: " + configuredValidationMode, parameter, null);
                yield ValidationMode.HOLDOUT;
            }
        };
    }

    protected int treeNodeCount(GPIndividual individual) {
        return individual.trees[0].child.numNodes(GPNode.NODESEARCH_ALL);
    }

    protected double complexityPenalty(int nodeCount) {
        if (nodeCount <= complexityFreeNodes || complexityPenaltyFactor <= 0.0) {
            return 0.0;
        }
        return (nodeCount - complexityFreeNodes) * complexityPenaltyFactor;
    }

    private double predictedTradeReturn(double predictedValue, double entryPrice) {
        if (targetMode == TargetMode.RETURN) {
            return predictedValue;
        }
        return (predictedValue - entryPrice) / Math.max(Math.abs(entryPrice), PROBABLY_ZERO);
    }

    private double actualTradeReturn(double[][] data, int row, double entryPrice) {
        if (targetMode == TargetMode.RETURN) {
            return expectedTargetValue(data, row);
        }
        double actualTargetPrice = data[row + 1][targetColumnIndex];
        return (actualTargetPrice - entryPrice) / Math.max(Math.abs(entryPrice), PROBABLY_ZERO);
    }

    protected int signalSide(double predictedTradeReturn) {
        if (predictedTradeReturn > backtestSignalThreshold) {
            return 1;
        }
        if (predictedTradeReturn < -backtestSignalThreshold) {
            return -1;
        }
        return 0;
    }

    protected String signalLabel(int signalSide) {
        return switch (signalSide) {
            case 1 -> "LONG";
            case -1 -> "SHORT";
            default -> "FLAT";
        };
    }

    protected String datasetSymbol() {
        String fileName = Path.of(datasetPath).getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String summaryJson(GPIndividual individual, EvaluationSummary testingSummary, BacktestArtifacts backtest, LiveSignal liveSignal, int nodeCount) {
        BacktestResult r = backtest.result;
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"symbol\": ").append(json(datasetSymbol())).append(",\n");
        builder.append("  \"dataset\": ").append(json(datasetPath)).append(",\n");
        builder.append("  \"target\": ").append(json(targetColumnName)).append(",\n");
        builder.append("  \"entry_column\": ").append(json(backtestEntryColumnName)).append(",\n");
        builder.append("  \"training_rows\": ").append(trainingEndIndex).append(",\n");
        builder.append("  \"testing_rows\": ").append(stockData.length - testingStartIndex).append(",\n");
        builder.append("  \"node_count\": ").append(nodeCount).append(",\n");
        builder.append("  \"complexity_penalty\": ").append(complexityPenalty(nodeCount)).append(",\n");
        builder.append("  \"testing_hits\": ").append(testingSummary.hits).append(",\n");
        builder.append("  \"testing_cases\": ").append(testingSummary.evaluatedCases).append(",\n");
        builder.append("  \"testing_accuracy_pct\": ").append(percentage(testingSummary.hits, testingSummary.evaluatedCases)).append(",\n");
        builder.append("  \"directional_accuracy_pct\": ").append(percentage(testingSummary.directionalHits, testingSummary.evaluatedCases)).append(",\n");
        builder.append("  \"average_normalized_error\": ").append(testingSummary.averageNormalizedError).append(",\n");
        builder.append("  \"initial_capital\": ").append(r.initialCapital).append(",\n");
        builder.append("  \"final_equity\": ").append(r.finalEquity).append(",\n");
        builder.append("  \"total_return_pct\": ").append(r.totalReturnPct).append(",\n");
        builder.append("  \"max_drawdown_pct\": ").append(r.maxDrawdownPct).append(",\n");
        builder.append("  \"trades\": ").append(r.trades).append(",\n");
        builder.append("  \"wins\": ").append(r.wins).append(",\n");
        builder.append("  \"long_trades\": ").append(r.longTrades).append(",\n");
        builder.append("  \"short_trades\": ").append(r.shortTrades).append(",\n");
        builder.append("  \"flat_signals\": ").append(r.flatSignals).append(",\n");
        builder.append("  \"win_rate_pct\": ").append(r.winRatePct).append(",\n");
        builder.append("  \"average_net_trade_return\": ").append(r.averageNetTradeReturn).append(",\n");
        builder.append("  \"average_holding_bars\": ").append(r.averageHoldingBars).append(",\n");
        builder.append("  \"total_cost_rate\": ").append(r.totalCostRate).append(",\n");
        builder.append("  \"profit_factor\": ").append(r.profitFactor).append(",\n");
        builder.append("  \"sharpe\": ").append(jsonNumber(r.sharpe)).append(",\n");
        builder.append("  \"sortino\": ").append(jsonNumber(r.sortino)).append(",\n");
        builder.append("  \"calmar\": ").append(jsonNumber(r.calmar)).append(",\n");
        builder.append("  \"cagr\": ").append(jsonNumber(r.cagr)).append(",\n");
        builder.append("  \"turnover\": ").append(r.turnover).append(",\n");
        builder.append("  \"model_expression\": ").append(json(individual.trees[0].child.makeCTree(true, true, true))).append(",\n");
        builder.append("  \"latest_signal\": ").append(latestSignalJsonBody(liveSignal)).append("\n");
        builder.append("}\n");
        return builder.toString();
    }

    private String jsonNumber(double value) {
        if (Double.isNaN(value)) return "null";
        if (Double.isInfinite(value)) return value > 0 ? "\"+Infinity\"" : "\"-Infinity\"";
        return Double.toString(value);
    }

    private String latestSignalJson(LiveSignal liveSignal) {
        return latestSignalJsonBody(liveSignal) + "\n";
    }

    private String latestSignalJsonBody(LiveSignal liveSignal) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"symbol\": ").append(json(liveSignal.symbol)).append(",\n");
        builder.append("  \"signal_timestamp\": ").append(json(liveSignal.signalTimestamp)).append(",\n");
        builder.append("  \"signal\": ").append(json(liveSignal.signal)).append(",\n");
        builder.append("  \"predicted_target_value\": ").append(liveSignal.predictedTargetValue).append(",\n");
        builder.append("  \"predicted_trade_return\": ").append(liveSignal.predictedTradeReturn).append(",\n");
        builder.append("  \"entry_column\": ").append(json(liveSignal.entryColumn)).append(",\n");
        builder.append("  \"target\": ").append(json(liveSignal.target)).append(",\n");
        builder.append("  \"alpaca_order_hint\": {\n");
        builder.append("    \"symbol\": ").append(json(liveSignal.symbol)).append(",\n");
        builder.append("    \"side\": ").append(json(alpacaSide(liveSignal.signal))).append(",\n");
        builder.append("    \"type\": ").append(json("market")).append(",\n");
        builder.append("    \"time_in_force\": ").append(json("opg")).append("\n");
        builder.append("  }\n");
        builder.append("}");
        return builder.toString();
    }

    private String tradingViewWebhookJson(LiveSignal liveSignal) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"symbol\": ").append(json(liveSignal.symbol)).append(",\n");
        builder.append("  \"signal\": ").append(json(liveSignal.signal)).append(",\n");
        builder.append("  \"predicted_trade_return\": ").append(liveSignal.predictedTradeReturn).append(",\n");
        builder.append("  \"predicted_target_value\": ").append(liveSignal.predictedTargetValue).append(",\n");
        builder.append("  \"signal_timestamp\": ").append(json(liveSignal.signalTimestamp)).append("\n");
        builder.append("}\n");
        return builder.toString();
    }

    private String signalsCsv(BacktestArtifacts backtest) {
        StringBuilder builder = new StringBuilder();
        builder.append("signal_timestamp,execution_timestamp,entry_price,predicted_target_value,actual_target_value,predicted_trade_return,actual_trade_return,signal,gross_strategy_return,net_strategy_return,pnl,equity\n");
        List<BarRecord> records = backtest.result.bars;
        for (int i = 0; i < records.size(); i++) {
            BarRecord bar = records.get(i);
            double actualTarget = backtest.actualTargetValues.get(i);
            builder.append(bar.signalTimestamp).append(',')
                    .append(bar.executionTimestamp).append(',')
                    .append(bar.entryPrice).append(',')
                    .append(backtest.predictedValues.get(i)).append(',')
                    .append(Double.isNaN(actualTarget) ? "" : Double.toString(actualTarget)).append(',')
                    .append(backtest.predictedTradeReturns.get(i)).append(',')
                    .append(backtest.actualTradeReturns.get(i)).append(',')
                    .append(signalLabel(bar.signal)).append(',')
                    .append(bar.grossStrategyReturn).append(',')
                    .append(bar.netStrategyReturn).append(',')
                    .append(bar.pnl).append(',')
                    .append(bar.equity)
                    .append('\n');
        }
        return builder.toString();
    }

    private String equityCsv(BacktestArtifacts backtest) {
        StringBuilder builder = new StringBuilder();
        builder.append("execution_timestamp,equity\n");
        for (EquityPoint point : backtest.result.equityCurve) {
            builder.append(point.executionTimestamp).append(',').append(point.equity).append('\n');
        }
        return builder.toString();
    }

    private String alpacaSide(String signal) {
        return switch (signal) {
            case "LONG" -> "buy";
            case "SHORT" -> "sell";
            default -> "hold";
        };
    }

    private String json(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private static final class EvaluationSummary {
        private final double averageNormalizedError;
        private final int hits;
        private final int directionalHits;
        private final int evaluatedCases;
        private final double totalNormalizedError;

        private EvaluationSummary(double averageNormalizedError, int hits, int evaluatedCases) {
            this(averageNormalizedError, hits, 0, evaluatedCases, averageNormalizedError * evaluatedCases);
        }

        private EvaluationSummary(double averageNormalizedError, int hits, int directionalHits, int evaluatedCases, double totalNormalizedError) {
            this.averageNormalizedError = averageNormalizedError;
            this.hits = hits;
            this.directionalHits = directionalHits;
            this.evaluatedCases = evaluatedCases;
            this.totalNormalizedError = totalNormalizedError;
        }

        private EvaluationSummary combine(EvaluationSummary other) {
            return new EvaluationSummary(
                    0.0,
                    this.hits + other.hits,
                    this.directionalHits + other.directionalHits,
                    this.evaluatedCases + other.evaluatedCases,
                    this.totalNormalizedError + other.totalNormalizedError
            );
        }
    }

    public enum ValidationMode {
        HOLDOUT,
        WALK_FORWARD
    }

    public enum TargetMode {
        PRICE,
        RETURN
    }

    private static final class TargetSpec {
        private final int columnIndex;
        private final String name;
        private final TargetMode mode;

        private TargetSpec(int columnIndex, String name, TargetMode mode) {
            this.columnIndex = columnIndex;
            this.name = name;
            this.mode = mode;
        }
    }

    private static final class TradingColumnSpec {
        private final int columnIndex;
        private final String name;

        private TradingColumnSpec(int columnIndex, String name) {
            this.columnIndex = columnIndex;
            this.name = name;
        }
    }

    protected static final class ParsedDataset {
        public final double[][] values;
        public final String[] timestamps;

        ParsedDataset(double[][] values, String[] timestamps) {
            this.values = values;
            this.timestamps = timestamps;
        }
    }

    private static final class BacktestArtifacts {
        private final BacktestResult result;
        private final List<Double> predictedValues;
        private final List<Double> predictedTradeReturns;
        private final List<Double> actualTradeReturns;
        private final List<Double> actualTargetValues;

        private BacktestArtifacts(BacktestResult result,
                                  List<Double> predictedValues,
                                  List<Double> predictedTradeReturns,
                                  List<Double> actualTradeReturns,
                                  List<Double> actualTargetValues) {
            this.result = result;
            this.predictedValues = predictedValues;
            this.predictedTradeReturns = predictedTradeReturns;
            this.actualTradeReturns = actualTradeReturns;
            this.actualTargetValues = actualTargetValues;
        }
    }

    private static final class LiveSignal {
        private final String symbol;
        private final String signalTimestamp;
        private final String signal;
        private final double predictedTargetValue;
        private final double predictedTradeReturn;
        private final String entryColumn;
        private final String target;

        private LiveSignal(String symbol, String signalTimestamp, String signal, double predictedTargetValue,
                           double predictedTradeReturn, String entryColumn, String target) {
            this.symbol = symbol;
            this.signalTimestamp = signalTimestamp;
            this.signal = signal;
            this.predictedTargetValue = predictedTargetValue;
            this.predictedTradeReturn = predictedTradeReturn;
            this.entryColumn = entryColumn;
            this.target = target;
        }
    }
}
