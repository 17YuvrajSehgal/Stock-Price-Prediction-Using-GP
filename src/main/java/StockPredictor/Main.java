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
    private static final String PATH = "src/main/data/SandP500.csv";
    public static void main(String[] args) {
        String pathToFiles = "src/main/results/";
        int numberOfJobs = 10;
        //String statisticType = "ec.gp.koza.KozaShortStatistics";
        String statisticType = "ec.simple.SimpleStatistics";

        String[] runConfig = new String[] {
                Evolve.A_FILE, "src/main/resources/stock.params",
                "-p", ("stat="+statisticType),
                "-p", ("stat.file=$"+pathToFiles+"out.stat"),
                "-p", ("jobs="+numberOfJobs)
        };
        Evolve.main(runConfig);
    }
}
