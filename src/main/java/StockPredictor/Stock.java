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
    public double open, high, low, close, adjustedClose, volume, movingTenDayAvg, movingFiftyDayAvg;     //parameters of stock
    public static final String P_DATA = "data";
    private static final double ACCEPTED_ERROR = 0.05;
    private final int TOTAL_NUM_OF_DATA_ROWS = 9564; //total rows of data
    private final int NUM_OF_DATA_FIELDS = 7; //total columns of data
    String[][] stockData = new String[TOTAL_NUM_OF_DATA_ROWS][NUM_OF_DATA_FIELDS]; //2d array to store rice data
    String[][] trainingData, testingData;
    private final String PATH = "src/main/data/MSFT.csv";


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
                this.close = Double.parseDouble(trainingData[i][4]);
                this.adjustedClose = Double.parseDouble(trainingData[i][5]);
                this.volume = Double.parseDouble(trainingData[i][6]);
                this.movingTenDayAvg = tenDayMovingAvg(trainingData,i,5);
                this.movingFiftyDayAvg = fiftyDayMovingAvg(trainingData,i,5);
                //System.out.println("adjusted close : "+adjustedClose+" moving 10 : "+movingTenDayAvg);
                //let's say we want to get the adjusted close right now for everyday
                double expectedResult = Double.parseDouble(trainingData[i + 1][1]);
                ((GPIndividual) individual).trees[0].child.eval(evolutionState, threadNum, this.input, this.stack, (GPIndividual) individual, this);

                double currentError = Math.abs(expectedResult - input.x);
                double currentErrorRatio = Math.abs(expectedResult - input.x)/expectedResult;

                //System.out.println("current value: "+input.x+"\nexpectedResult: " + expectedResult+"\nerror: "+currentErrorRatio*100);
                if (currentErrorRatio <= ACCEPTED_ERROR) {
                    ++hits;
                }
                sum+=currentError;
            }

            //set koza statistics
            //hits = getHits(evolutionState, (GPIndividual) individual,threadNum,input,hits,expectedResult);
            KozaFitness kozaFitness = ((KozaFitness) individual.fitness);
            kozaFitness.setStandardizedFitness(evolutionState, sum);
            kozaFitness.hits = hits;
            individual.evaluated = true;
        }
    }



    /**
     * Describes the best individual.
     *
     * @param state         The current EvolutionState.
     * @param bestIndividual    The best individual.
     * @param subpopulation The subpopulation index.
     * @param threadnum     The thread number.
     * @param log           Additional parameter (not used).
     */
    @Override
    public void describe(EvolutionState state, Individual bestIndividual, int subpopulation, int threadnum, int log) {
        super.describe(state, bestIndividual, subpopulation, threadnum, log);
        System.out.println("running describe");
        if (!bestIndividual.evaluated) {
            DoubleData input = (DoubleData) this.input;
            double sum = 0;
            int hits = 0;

            for (int i = 0; i < testingData.length - 2; i++) {

                //date = Double.parseDouble(stockData[i][0]);
                this.open = Double.parseDouble(testingData[i][1]);
                this.high = Double.parseDouble(testingData[i][2]);
                this.low = Double.parseDouble(testingData[i][3]);
                this.close = Double.parseDouble(testingData[i][4]);
                this.adjustedClose = Double.parseDouble(testingData[i][5]);
                this.volume = Double.parseDouble(testingData[i][6]);
                this.movingTenDayAvg = tenDayMovingAvg(testingData,i,5);
                this.movingFiftyDayAvg = fiftyDayMovingAvg(testingData,i,5);

                //let's say we want to get the adjusted close right now for everyday
                double expectedResult = Double.parseDouble(testingData[i + 1][1]);
                ((GPIndividual) bestIndividual).trees[0].child.eval(state, threadnum, this.input, this.stack, (GPIndividual) bestIndividual, this);

                double currentError = Math.abs(expectedResult - input.x);

                //System.out.println("current value: "+input.x+"\nexpectedResult: " + expectedResult);

                if (currentError <= ACCEPTED_ERROR) {
                    //System.out.println(hits+" hits");
                    ++hits;
                }
                sum += currentError;

            }

            //set koza statistics
            //hits = getHits(evolutionState, (GPIndividual) individual,threadNum,input,hits,expectedResult);
            KozaFitness kozaFitness = ((KozaFitness) bestIndividual.fitness);
            kozaFitness.setStandardizedFitness(state, sum);
            kozaFitness.hits = hits;
            bestIndividual.evaluated = true;
        }
    }
    /**
     * This method setups the GA problem by reading the data file and initializing the problem
     * @param state current state
     * @param base current base
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
     * @param percentage percentage of data to put in training and remaining in testing
     */
    public void splitData(double percentage){

        final int TRAINING_DATA_ROWS = (int) Math.round(TOTAL_NUM_OF_DATA_ROWS * percentage);
        final int TESTING_DATA_ROWS = (int) Math.round(TOTAL_NUM_OF_DATA_ROWS - TOTAL_NUM_OF_DATA_ROWS * percentage);

        this.trainingData = new String[TRAINING_DATA_ROWS][NUM_OF_DATA_FIELDS];
        this.testingData = new String[TESTING_DATA_ROWS][NUM_OF_DATA_FIELDS];

        System.arraycopy(this.stockData, 0, trainingData, 0, TRAINING_DATA_ROWS);
        System.arraycopy(this.stockData,TRAINING_DATA_ROWS,testingData,0,TESTING_DATA_ROWS);

        //System.out.println("Training data"+trainingData.length);
        //printArray(trainingData);
        //System.out.println("Training data"+testingData.length);
        //printArray(testingData);
    }

    /**
     * This method reads the csv files and creates a 2d array of stock Data
     * @param path path of the csv file
     */
    public void fileReader(String path) {
        AtomicInteger rowNumber= new AtomicInteger();
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

        splitData(0.5);
    }

    /**
     * This is a utility function to calculate the total number correct predictions
     * @param state state
     * @param bestIndividual best individual of the current population
     * @param threadnum number of thread
     * @param input input
     * @param hits number of hits
     * @param expectedResult expected result
     * @return number of hits
     */
    private int getHits(EvolutionState state, GPIndividual bestIndividual, int threadnum, DoubleData input, int hits, double expectedResult) {
        bestIndividual.trees[0].child.eval(
                state, threadnum, input, stack, bestIndividual, this);

        double predictedResult = input.x;
        if (Math.abs(predictedResult - expectedResult) < ACCEPTED_ERROR) {
            hits++;
        }
        return hits;
    }

    /**
     * Prints the contents of the ground array.
     */
    public void printArray(String[][] data){
        Arrays.stream(data)
                .map(Arrays::toString)
                .forEach(System.out::println);
    }

    private double tenDayMovingAvg(String[][] stockData, int row, int col){
        // if the values are first 10 rows
        if(row < 10){
            double sum = 0;
            for(int i = 0; i < row; i++){
                sum += Double.parseDouble(stockData[i][col]);
            }
            return sum / row;
        }
        // if the values are last 10 rows
        if(row + 10 > stockData.length){
            double sum = 0;
            for(int i = row; i < stockData.length; i++){
                sum += Double.parseDouble(stockData[i][col]);
            }
            return sum / (stockData.length - row);
        }
        double sum = 0;
        for(int i = row; i < row + 10; i++){
            sum += Double.parseDouble(stockData[i][col]);
        }
        return sum / 10;
    }


    private double fiftyDayMovingAvg(String[][] stockData, int row, int col){
        //if the values are first 50 rows
        if(row<50){
            double sum = 0;
            for(int i=0;i<row;i++){
                sum+=Double.parseDouble(stockData[i][col]);
            }
            return sum/row;
        }
        //if the values are last 10 rows
        if(row+50>stockData.length){
            double sum = 0;
            for(int i=row;i<stockData.length;i++){
                sum += Double.parseDouble(stockData[i][col]);
            }
            return sum/(stockData.length-row);
        }
        double sum = 0;

        for(int i=row;i<row+50;i++){
            sum+= Double.parseDouble(stockData[i][col]);
        }
        return sum;
    }
}
