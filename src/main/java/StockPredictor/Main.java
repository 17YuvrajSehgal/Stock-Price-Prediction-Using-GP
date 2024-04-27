package StockPredictor;
import ec.Evolve;


/**
 * This is the main class
 */
public class Main {
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
}
