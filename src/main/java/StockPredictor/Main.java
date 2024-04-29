package StockPredictor;
import ec.Evolve;


/**
 * This is the main class
 */
public class Main {
    private static final String PATH = "src/main/data/SandP500.csv";
    public static void main(String[] args) {
        String pathToFiles = "src/main/results/";
        int numberOfJobs = 1;
        String statisticType1 = "ec.gp.koza.KozaShortStatistics";
        String statisticType2 = "ec.simple.SimpleStatistics";

        String[] runConfig = new String[] {
                Evolve.A_FILE, "src/main/resources/stock.params",
                "-p", ("stat="+statisticType1),
                "-p", ("stat.file=$"+pathToFiles+"out.stat"),
                "-p", ("jobs="+numberOfJobs),
                "-p", ("stat.do-message="+true),

                "-p", ("stat.num-children="+1),
                "-p", ("stat.child.0="+statisticType2),
                "-p", ("stat.child.0.file=$"+pathToFiles+"out2.stat"),
                //"-p", ("stat.child.0.do-size"+ true)

                //"-p", ("stat.do-generation="+false)
        };
        Evolve.main(runConfig);
    }
}
