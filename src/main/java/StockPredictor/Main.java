package StockPredictor;
import ec.Evolve;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * This is the main class
 */
public class Main {

    public String date;
    public double open, high, low, close, adjustedClose, volume;     //parameters of stock
    public static final String P_DATA = "data";
    private static final double ACCEPTED_ERROR = 1;
    private static final int TOTAL_NUM_OF_DATA_ROWS = 18698; //total rows of data
    private static final int NUM_OF_DATA_FIELDS = 7; //total columns of data
    static String[][] stockData = new String[TOTAL_NUM_OF_DATA_ROWS][NUM_OF_DATA_FIELDS]; //2d array to store rice data
    String[][] trainingData, testingData;
    private static final String PATH = "src/main/data/SandP500.csv";
    public static void main(String[] args) {
        String pathToFiles = "src/main/results/";
        int numberOfJobs = 1;
        String statisticType = "ec.gp.koza.KozaShortStatistics";
        String[] runConfig = new String[] {
                Evolve.A_FILE, "src/main/resources/stock.params",
                //"-p", ("stat="+statisticType),
                "-p", ("stat.file=$"+pathToFiles+"out.stat"),
                "-p", ("jobs="+numberOfJobs)
        };
        Evolve.main(runConfig);
    }

//    public static void fileReader(String path) {
//        AtomicInteger rowNumber= new AtomicInteger();
//        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
//            br.lines()
//                    .skip(1) // Skip the header row
//                    .map(line -> line.split(","))
//                    .forEach(parts -> {
//                        if (parts.length != NUM_OF_DATA_FIELDS) {
//                            throw new IllegalArgumentException("Invalid number of fields in row");
//                        }
//                        System.out.println(parts[0]+ " "+ parts[1]+ " "+ parts[2]+ " "+ parts[3]+ " "+ parts[4]+ " "+ parts[5]+ " "+ parts[6]);
//                        System.arraycopy(parts, 0, stockData[rowNumber.getAndIncrement()], 0, NUM_OF_DATA_FIELDS);
//                    });
//
//            System.out.println("training data");
//            System.out.println("testing data");
//
//        } catch (IOException e) {
//            throw new RuntimeException("Error reading file", e);
//        }
//    }
}
