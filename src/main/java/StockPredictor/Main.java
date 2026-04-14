package StockPredictor;
import ec.Evolve;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This is the main class
 */
public class Main {
    private static final Path PARAMETER_FILE = Path.of("src/main/resources/stock-full.params").toAbsolutePath().normalize();
    private static final Path RESULTS_DIRECTORY = Path.of("src/main/results").toAbsolutePath().normalize();

    public static void main(String[] args) {
        ensureResultsDirectory();

        int numberOfJobs = 1;
        String statisticType1 = "ec.gp.koza.KozaShortStatistics";
        String statisticType2 = "ec.simple.SimpleStatistics";

        List<String> runConfig = new ArrayList<>(Arrays.asList(
                Evolve.A_FILE, PARAMETER_FILE.toString(),
                "-p", ("stat=" + statisticType1),
                "-p", ("stat.file=" + RESULTS_DIRECTORY.resolve("out.stat")),
                "-p", ("jobs=" + numberOfJobs),
                "-p", ("stat.do-message=" + true),
                "-p", ("stat.num-children=" + 1),
                "-p", ("stat.child.0=" + statisticType2),
                "-p", ("stat.child.0.file=" + RESULTS_DIRECTORY.resolve("out2.stat"))
        ));
        runConfig.addAll(Arrays.asList(args));
        Evolve.main(runConfig.toArray(new String[0]));
    }

    private static void ensureResultsDirectory() {
        try {
            Files.createDirectories(RESULTS_DIRECTORY);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create results directory: " + RESULTS_DIRECTORY, e);
        }
    }
}
