package StockPredictor;

import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.GPProblem;
import ec.gp.koza.KozaFitness;
import ec.simple.SimpleProblemForm;
import ec.util.Parameter;
import terminal.DoubleData;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    public double open, high, low, close, adjustedClose, volume, movingTenDayAvg, movingFiftyDayAvg;
    public double previousOpen, previousClose, dailyRange, intradayChange, volumeTenDayAvg;
    public double laggedOpenReturn, laggedCloseReturn, closeVolatilityTenDay, closeVolatilityTwentyDay;

    private double[][] stockData = new double[0][0];
    private double[][] trainingData = new double[0][0];
    private double[][] testingData = new double[0][0];
    private String datasetPath = DEFAULT_DATASET;
    private int targetColumnIndex = OPEN_INDEX;
    private String targetColumnName = DEFAULT_TARGET_COLUMN;
    private TargetMode targetMode = TargetMode.PRICE;
    private double acceptedError = DEFAULT_HIT_THRESHOLD;
    private ValidationMode validationMode = ValidationMode.HOLDOUT;
    private int walkForwardTrainingRows = DEFAULT_WALK_FORWARD_TRAINING_ROWS;
    private int walkForwardTestRows = DEFAULT_WALK_FORWARD_TEST_ROWS;
    private int walkForwardStepRows = DEFAULT_WALK_FORWARD_STEP_ROWS;


    /**
     * Evaluates the fitness of an individual.
     *
     * @param evolutionState The current EvolutionState.
     * @param individual     The individual to evaluate.
     * @param threadNum      The thread number.
     * @param subPopulation  The subpopulation (not used).
     */
    @Override
    public void evaluate(EvolutionState evolutionState, Individual individual, int subPopulation, int threadNum) {
        if (!individual.evaluated) {
            EvaluationSummary summary = evaluateTrainingFitness(evolutionState, (GPIndividual) individual, threadNum);
            KozaFitness kozaFitness = ((KozaFitness) individual.fitness);
            kozaFitness.setStandardizedFitness(evolutionState, summary.averageNormalizedError);
            kozaFitness.hits = summary.hits;
            individual.evaluated = true;
        }
    }


    /**
     * Describes the best individual.
     *
     * @param state          The current EvolutionState.
     * @param bestIndividual The best individual.
     * @param subpopulation  The subpopulation index.
     * @param threadnum      The thread number.
     * @param log            Additional parameter (not used).
     */
    @Override
    public void describe(EvolutionState state, Individual bestIndividual, int subpopulation, int threadnum, int log) {
        super.describe(state, bestIndividual, subpopulation, threadnum, log);

        if (!(bestIndividual instanceof GPIndividual))
            state.output.fatal("The best individual is not an instance of GPIndividual!!");

        EvaluationSummary summary = evaluateDataset(state, (GPIndividual) bestIndividual, threadnum, testingData);
        state.output.println("Dataset: " + datasetPath, log);
        state.output.println("Prediction target: " + targetColumnName, log);
        state.output.println("Best Individual's testing hits: " + summary.hits + " out of " + summary.evaluatedCases, log);
        state.output.println("Best Individual's testing accuracy: " + percentage(summary.hits, summary.evaluatedCases) + "%", log);
        state.output.println("Best Individual's directional accuracy: " + percentage(summary.directionalHits, summary.evaluatedCases) + "%", log);
        state.output.println("Best Individual's average normalized error: " + summary.averageNormalizedError, log);
    }

    /**
     * This method setups the GA problem by reading the data file and initializing the problem
     *
     * @param state current state
     * @param base  current base
     */
    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);
        if (!(input instanceof DoubleData)) {
            state.output.fatal("GPData class must subclass from " + DoubleData.class,
                    base.push(P_DATA), null);
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

        stockData = readCsv(datasetPath, state);
        splitData(trainingSplit, state);
    }

    /**
     * This method splits the data into 2 arrays. the testing and training array
     *
     * @param percentage percentage of data to put in training and remaining in testing
     */
    public void splitData(double percentage, EvolutionState state) {
        int trainingRows = (int) Math.round(stockData.length * percentage);
        trainingRows = Math.max(1, Math.min(trainingRows, stockData.length - 1));
        int testingRows = stockData.length - trainingRows;

        this.trainingData = Arrays.copyOfRange(this.stockData, 0, trainingRows);
        this.testingData = Arrays.copyOfRange(this.stockData, trainingRows, stockData.length);

        if (trainingData.length <= MIN_ROWS_FOR_FEATURES || testingData.length <= MIN_ROWS_FOR_FEATURES) {
            state.output.warning("One of the dataset splits has too few rows for the configured moving averages. Results may be unstable.");
        }

        state.output.message("Loaded dataset: " + datasetPath);
        state.output.message("Training rows: " + trainingData.length);
        state.output.message("Testing rows: " + testingData.length);
        state.output.message("Prediction target: " + targetColumnName);
        state.output.message("Hit threshold: " + acceptedError);
        state.output.message("Validation mode: " + validationMode.name().toLowerCase());
        if (validationMode == ValidationMode.WALK_FORWARD) {
            if (trainingData.length <= walkForwardTrainingRows + walkForwardTestRows) {
                state.output.fatal("Training split is too small for the configured walk-forward window sizes.");
            }
            state.output.message("Walk-forward training rows: " + walkForwardTrainingRows);
            state.output.message("Walk-forward test rows: " + walkForwardTestRows);
            state.output.message("Walk-forward step rows: " + walkForwardStepRows);
        }
    }

    /**
     * This method reads the csv files and creates a 2d array of stock Data
     *
     * @param path path of the csv file
     */
    public double[][] readCsv(String path, EvolutionState state) {
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

                rows.add(parseNumericColumns(parts));
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading file", e);
        }

        if (rows.size() < 2) {
            state.output.fatal("Dataset must contain at least two data rows: " + path);
        }

        return rows.toArray(new double[0][]);
    }

    /**
     * Prints the contents of the ground array.
     */
    public void printArray(double[][] data) {
        Arrays.stream(data)
                .map(Arrays::toString)
                .forEach(System.out::println);
    }

    /**
     * This method calculated the n-day moving average, if the data is <n-day it will return moving avg
     * till return data.length as n day length
     *
     * @param data stock data
     * @param nDay n days moving avg. e.g. past 10 day moving average
     * @param row  row's from which moving avg will be calculated
     * @param col  col's for which moving avg will be calculated
     * @return moving average of given data
     */
    private double nDayMovingAverage(double[][] data, int nDay, int row, int col) {
        if (row < nDay - 1) {
            return Double.NaN;
        }

        double sum = 0;

        for (int i = row - nDay + 1; i <= row; i++) {
            sum += data[i][col];
        }

        return sum / nDay;
    }

    private EvaluationSummary evaluateDataset(EvolutionState state, GPIndividual individual, int threadNum, double[][] data) {
        return evaluateDatasetRange(state, individual, threadNum, data, 0, data.length - 1);
    }

    private EvaluationSummary evaluateTrainingFitness(EvolutionState state, GPIndividual individual, int threadNum) {
        if (validationMode == ValidationMode.WALK_FORWARD) {
            return evaluateWalkForward(state, individual, threadNum, trainingData);
        }
        return evaluateDataset(state, individual, threadNum, trainingData);
    }

    private EvaluationSummary evaluateWalkForward(EvolutionState state, GPIndividual individual, int threadNum, double[][] data) {
        EvaluationSummary aggregate = new EvaluationSummary(0.0, 0, 0);
        int windowStart = 0;
        int lastTrainingRow = walkForwardTrainingRows - 1;

        while (lastTrainingRow + walkForwardTestRows < data.length) {
            int evaluationStart = lastTrainingRow;
            int evaluationEndExclusive = Math.min(evaluationStart + walkForwardTestRows, data.length - 1);
            EvaluationSummary windowSummary = evaluateDatasetRange(state, individual, threadNum, data, evaluationStart, evaluationEndExclusive);
            aggregate = aggregate.combine(windowSummary);

            windowStart += walkForwardStepRows;
            lastTrainingRow = windowStart + walkForwardTrainingRows - 1;
        }

        if (aggregate.evaluatedCases == 0) {
            return new EvaluationSummary(BIG_NUMBER, 0, 0);
        }

        return new EvaluationSummary(aggregate.totalNormalizedError / aggregate.evaluatedCases, aggregate.hits, aggregate.evaluatedCases);
    }

    private EvaluationSummary evaluateDatasetRange(EvolutionState state, GPIndividual individual, int threadNum, double[][] data, int startRow, int endExclusive) {
        DoubleData input = (DoubleData) this.input;
        double totalNormalizedError = 0.0;
        int hits = 0;
        int directionalHits = 0;
        int evaluatedCases = 0;

        for (int i = Math.max(0, startRow); i < Math.min(endExclusive, data.length - 1); i++) {
            if (!loadRowContext(data, i)) {
                continue;
            }

            double currentValue = data[i][targetColumnIndex];
            double expectedResult = expectedTargetValue(data, i);
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

    private boolean loadRowContext(double[][] data, int row) {
        this.open = data[row][OPEN_INDEX];
        this.high = data[row][HIGH_INDEX];
        this.low = data[row][LOW_INDEX];
        this.close = data[row][CLOSE_INDEX];
        this.adjustedClose = data[row][ADJUSTED_CLOSE_INDEX];
        this.volume = data[row][VOLUME_INDEX];
        this.previousOpen = row > 0 ? data[row - 1][OPEN_INDEX] : data[row][OPEN_INDEX];
        this.previousClose = row > 0 ? data[row - 1][CLOSE_INDEX] : data[row][CLOSE_INDEX];
        this.dailyRange = this.high - this.low;
        this.intradayChange = this.close - this.open;
        this.laggedOpenReturn = laggedReturn(data, row, OPEN_INDEX);
        this.laggedCloseReturn = laggedReturn(data, row, CLOSE_INDEX);
        this.movingTenDayAvg = nDayMovingAverage(data, 10, row, ADJUSTED_CLOSE_INDEX);
        this.movingFiftyDayAvg = nDayMovingAverage(data, 50, row, ADJUSTED_CLOSE_INDEX);
        this.volumeTenDayAvg = nDayMovingAverage(data, 10, row, VOLUME_INDEX);
        this.closeVolatilityTenDay = rollingVolatility(data, 10, row, CLOSE_INDEX);
        this.closeVolatilityTwentyDay = rollingVolatility(data, 20, row, CLOSE_INDEX);
        return !Double.isNaN(this.movingTenDayAvg)
                && !Double.isNaN(this.movingFiftyDayAvg)
                && !Double.isNaN(this.volumeTenDayAvg)
                && !Double.isNaN(this.closeVolatilityTenDay)
                && !Double.isNaN(this.closeVolatilityTwentyDay);
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

    private double laggedReturn(double[][] data, int row, int col) {
        if (row < 1) {
            return Double.NaN;
        }
        double previous = data[row - 1][col];
        double current = data[row][col];
        return (current - previous) / Math.max(Math.abs(previous), PROBABLY_ZERO);
    }

    private double rollingVolatility(double[][] data, int window, int row, int col) {
        if (row < window) {
            return Double.NaN;
        }

        double mean = 0.0;
        for (int i = row - window + 1; i <= row; i++) {
            mean += laggedReturn(data, i, col);
        }
        mean /= window;

        double variance = 0.0;
        for (int i = row - window + 1; i <= row; i++) {
            double value = laggedReturn(data, i, col) - mean;
            variance += value * value;
        }
        variance /= window;
        return Math.sqrt(variance);
    }

    private double percentage(int numerator, int denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return (numerator * 100.0) / denominator;
    }

    private TargetSpec resolveTargetSpec(String configuredTargetColumn, EvolutionState state, Parameter parameter) {
        return switch (configuredTargetColumn.toLowerCase()) {
            case "open" -> new TargetSpec(OPEN_INDEX, "open", TargetMode.PRICE);
            case "high" -> new TargetSpec(HIGH_INDEX, "high", TargetMode.PRICE);
            case "low" -> new TargetSpec(LOW_INDEX, "low", TargetMode.PRICE);
            case "close" -> new TargetSpec(CLOSE_INDEX, "close", TargetMode.PRICE);
            case "adjusted-close", "adjusted_close", "adj-close", "adj_close" -> new TargetSpec(ADJUSTED_CLOSE_INDEX, "adjusted-close", TargetMode.PRICE);
            case "volume" -> new TargetSpec(VOLUME_INDEX, "volume", TargetMode.PRICE);
            case "open-return", "open_return", "return-open", "return_open" -> new TargetSpec(OPEN_INDEX, "open-return", TargetMode.RETURN);
            case "close-return", "close_return", "return-close", "return_close" -> new TargetSpec(CLOSE_INDEX, "close-return", TargetMode.RETURN);
            case "adjusted-close-return", "adjusted_close_return", "adj-close-return", "adj_close_return" ->
                    new TargetSpec(ADJUSTED_CLOSE_INDEX, "adjusted-close-return", TargetMode.RETURN);
            default -> {
                state.output.fatal("Unsupported target-column: " + configuredTargetColumn, parameter, null);
                yield new TargetSpec(OPEN_INDEX, "open", TargetMode.PRICE);
            }
        };
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
        return switch (configuredValidationMode.toLowerCase()) {
            case "holdout" -> ValidationMode.HOLDOUT;
            case "walk-forward", "walk_forward" -> ValidationMode.WALK_FORWARD;
            default -> {
                state.output.fatal("Unsupported validation-mode: " + configuredValidationMode, parameter, null);
                yield ValidationMode.HOLDOUT;
            }
        };
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
            return new EvaluationSummary(0.0, this.hits + other.hits, this.directionalHits + other.directionalHits, this.evaluatedCases + other.evaluatedCases,
                    this.totalNormalizedError + other.totalNormalizedError);
        }
    }

    private enum ValidationMode {
        HOLDOUT,
        WALK_FORWARD
    }

    private enum TargetMode {
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
}
