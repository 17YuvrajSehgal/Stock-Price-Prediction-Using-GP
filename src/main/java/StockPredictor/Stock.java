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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class Stock extends GPProblem implements SimpleProblemForm {

    //public String date;
    public double open, high, low, close, volume, movingTenDayAvg, movingFiftyDayAvg;     //parameters of stock
    public static final String P_DATA = "data";
    private static final double ACCEPTED_ERROR = 0.001;
    private static final double PROBABLY_ZERO = 1.11E-15;
    private static final double BIG_NUMBER = 1.0E15;
    private final int TOTAL_NUM_OF_DATA_ROWS = 6583; //total rows of data
    private final int NUM_OF_DATA_FIELDS = 6; //total columns of data
    String[][] stockData = new String[TOTAL_NUM_OF_DATA_ROWS][NUM_OF_DATA_FIELDS]; //2d array to store rice data
    String[][] trainingData, testingData;
    private final String PATH = "src/main/data/MSFT_1min_sample.csv";


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
            DoubleData input = (DoubleData) this.input;
            double sum = 0;
            int hits = 0;

            for (int i = 0; i < trainingData.length - 2; i++) {

                //date = Double.parseDouble(stockData[i][0]);
                this.open = Double.parseDouble(trainingData[i][1]);
                this.high = Double.parseDouble(trainingData[i][2]);
                this.low = Double.parseDouble(trainingData[i][3]);
                //this.close = Double.parseDouble(trainingData[i][4]);
                this.volume = Double.parseDouble(trainingData[i][5]);
                this.movingTenDayAvg = nDayMovingAverage2(trainingData, 10, i, 5);
                this.movingFiftyDayAvg = nDayMovingAverage2(trainingData, 50, i, 5);

                double expectedResult = Double.parseDouble(trainingData[i + 1][4]);

                //System.out.println("row : "+i+" adjusted close : "+adjustedClose+" moving 10 : "+movingTenDayAvg + " moving 50 : "+movingFiftyDayAvg);
                //let's say we want to get the adjusted close right now for everyday

                ((GPIndividual) individual).trees[0].child.eval(evolutionState, threadNum, this.input, this.stack, (GPIndividual) individual, this);

                double result = Math.abs(expectedResult - input.x); //todo
                double currentErrorRatio = Math.abs(expectedResult - input.x) / expectedResult;

                //System.out.println("current value: "+input.x+"\nexpectedResult: " + expectedResult);
                if (result >= BIG_NUMBER) {
                    result = BIG_NUMBER;
                } else if (result < PROBABLY_ZERO) {
                    result = 0.0;
                }
                hits = getHits(evolutionState, (GPIndividual) individual, threadNum, input, hits, expectedResult);

                sum += result;
            }

            //set koza statistics
            //hits = getHits(evolutionState, (GPIndividual) individual,threadNum,input,hits,expectedResult);
            KozaFitness kozaFitness = ((KozaFitness) individual.fitness);
            //System.out.println("sum is : "+sum);
            kozaFitness.setStandardizedFitness(evolutionState, sum);
            kozaFitness.hits = hits;
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
        state.output.println("running describe", log);

        if (!(bestIndividual instanceof GPIndividual))
            state.output.fatal("The best individual is not an instance of GPIndividual!!");

        DoubleData input = (DoubleData) this.input;
        int hits = 0;
        double expectedResult;

        for (int i = 0; i < testingData.length - 2; i++) {

            //date = Double.parseDouble(stockData[i][0]);
            this.open = Double.parseDouble(testingData[i][1]);
            this.high = Double.parseDouble(testingData[i][2]);
            this.low = Double.parseDouble(testingData[i][3]);
            //this.close = Double.parseDouble(testingData[i][4]);
            this.volume = Double.parseDouble(testingData[i][5]);
            this.movingTenDayAvg = nDayMovingAverage2(testingData, 10, i, 5);
            this.movingFiftyDayAvg = nDayMovingAverage2(testingData, 50, i, 5);

            expectedResult = Double.parseDouble(testingData[i + 1][4]);

            //System.out.println("row : "+i+" adjusted close : "+adjustedClose+" moving 10 : "+movingTenDayAvg + " moving 50 : "+movingFiftyDayAvg);
            //let's say we want to get the adjusted close right now for everyday

            assert bestIndividual instanceof GPIndividual;
            ((GPIndividual) bestIndividual).trees[0].child.eval(state, threadnum, this.input, this.stack, (GPIndividual) bestIndividual, this);

            hits = getHits(state, (GPIndividual) bestIndividual, threadnum, input, hits, expectedResult);
        }
        state.output.println("Best Individual's total correct hits: " + hits + " out of " + this.testingData.length, log);
        state.output.println("Best Individual's testing correctness: " + ((double) hits / (double) this.testingData.length) * 100 + "%", log);
    }

    private int getHits(EvolutionState state, GPIndividual bestIndividual, int threadnum, DoubleData input, int hits, double expectedResult) {
        bestIndividual.trees[0].child.eval(state, threadnum, input, stack, bestIndividual, this);

        //if the output of the rule is >=0 and that's what we were expecting increase hits
        double errorRatio = Math.abs(expectedResult-input.x)/expectedResult;
        if (errorRatio < ACCEPTED_ERROR) {
            hits++;
        }
        return hits;
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
        fileReader(PATH);
    }

    /**
     * This method splits the data into 2 arrays. the testing and training array
     *
     * @param percentage percentage of data to put in training and remaining in testing
     */
    public void splitData(double percentage) {
        final int TRAINING_DATA_ROWS = (int) Math.round(TOTAL_NUM_OF_DATA_ROWS * percentage);
        final int TESTING_DATA_ROWS = TOTAL_NUM_OF_DATA_ROWS - TRAINING_DATA_ROWS;

        this.trainingData = new String[TRAINING_DATA_ROWS][NUM_OF_DATA_FIELDS];
        this.testingData = new String[TESTING_DATA_ROWS][NUM_OF_DATA_FIELDS];

        System.arraycopy(this.stockData, 0, trainingData, 0, TRAINING_DATA_ROWS);
        System.arraycopy(this.stockData, TRAINING_DATA_ROWS, testingData, 0, TESTING_DATA_ROWS);
    }

    /**
     * This method reads the csv files and creates a 2d array of stock Data
     *
     * @param path path of the csv file
     */
    public void fileReader(String path) {
        AtomicInteger rowNumber = new AtomicInteger();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.lines()
                    .skip(1) // Skip the header row
                    .map(line -> line.split(","))
                    .forEach(parts -> {
                        if (parts.length != NUM_OF_DATA_FIELDS) {
                            throw new IllegalArgumentException("Invalid number of fields in row");
                        }
                        //System.out.println(parts[0]+ " "+ parts[1]+ " "+ parts[2]+ " "+ parts[3]+ " "+ parts[4]+ " "+ parts[5]+ " "+ parts[6]);
                        System.arraycopy(parts, 0, stockData[rowNumber.getAndIncrement()], 0, NUM_OF_DATA_FIELDS);
                    });
        } catch (IOException e) {
            throw new RuntimeException("Error reading file", e);
        }

        this.trainingData = stockData;
        splitData(0.5);
    }

    /**
     * Prints the contents of the ground array.
     */
    public void printArray(String[][] data) {
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
    private double nDayMovingAverage(String[][] data, int nDay, int row, int col) {
        if (row == 0) {
            return Double.parseDouble(data[row][col]);
        }
        if (row < nDay) {
            double sum = 0;
            for (int i = 0; i < row; i++) {
                sum += Double.parseDouble(data[i][col]);
            }
            return sum / row;
        }
        if (row + nDay > data.length) {
            double sum = 0;
            for (int i = row; i < data.length - 1; i++) { //todo check
                sum += Double.parseDouble(data[i][col]);
            }
            return sum / (data.length - row);
        }

        double sum = 0;

        for (int i = row; i < row + nDay; i++) {
            sum += Double.parseDouble(data[i][col]);
        }
        return sum / nDay;
    }

    private double nDayMovingAverage2(String[][] data, int nDay, int row, int col) {
        if (row < nDay - 1) {
            return -1; // or some other indicator that there isn't enough data to calculate the moving average
        }

        double sum = 0;

        for (int i = row - nDay + 1; i <= row; i++) {
            sum += Double.parseDouble(data[i][col]);
        }

        return sum / nDay;
    }
}
