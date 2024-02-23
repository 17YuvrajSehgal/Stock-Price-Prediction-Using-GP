package StockPredictor;

import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPProblem;
import ec.simple.SimpleProblemForm;

import java.io.*;
import java.nio.file.Path;
import java.util.Date;
import java.util.Scanner;

public class Stock extends GPProblem implements SimpleProblemForm {

    Date date;
    public double open, high, low, close, adjustedClose;
    public long volume;
    @Override
    public void evaluate(EvolutionState evolutionState, Individual individual, int i, int i1) {

    }

    public void fileReader(String path){

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Split the line by tab (,) delimiter
                String[] parts = line.split(",");
                // Print each part
                for (String part : parts) {
                    System.out.print(part + "\t");
                }
                System.out.println(); // Move to the next line
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
