# Stock Price Prediction Using GP

This project uses [ECJ](https://cs.gmu.edu/~eclab/projects/ecj/) genetic programming to evolve symbolic expressions for stock forecasting from OHLCV data and derived technical features.

## Profiles

- `src/main/resources/stock-quick.params`: fast local iteration with a smaller search budget.
- `src/main/resources/stock-full.params`: fuller experiment profile with walk-forward validation enabled.
- `src/main/resources/stock-return.params`: return-prediction profile built on the full profile.

## Targets

Set `eval.problem.target-column` to one of:

- `open`
- `high`
- `low`
- `close`
- `adjusted-close`
- `volume`
- `open-return`
- `close-return`
- `adjusted-close-return`

Price targets predict the next value directly. Return targets predict the next-step fractional return.

## Validation Modes

- `holdout`: fitness is computed on the training split only, with final reporting on the held-out test split.
- `walk-forward`: fitness is computed by rolling a training window through the training segment and evaluating on the following validation blocks.

Walk-forward controls:

- `eval.problem.walk-forward-training-rows`
- `eval.problem.walk-forward-test-rows`
- `eval.problem.walk-forward-step-rows`

## Running

Compile:

```powershell
javac -cp ecj.23.jar -d target/manual-classes (Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
```

Quick run:

```powershell
java -cp "target/manual-classes;ecj.23.jar" ec.Evolve -file src/main/resources/stock-quick.params
```

Full walk-forward run:

```powershell
java -cp "target/manual-classes;ecj.23.jar" ec.Evolve -file src/main/resources/stock-full.params
```

Return-prediction run:

```powershell
java -cp "target/manual-classes;ecj.23.jar" ec.Evolve -file src/main/resources/stock-return.params
```

Example override:

```powershell
java -cp "target/manual-classes;ecj.23.jar" ec.Evolve -file src/main/resources/stock-full.params -p eval.problem.dataset=src/main/data/AAPL.csv -p eval.problem.target-column=close
```

## Metrics

The project reports:

- threshold-based hit count
- average normalized error for price targets
- average absolute error for return targets
- directional accuracy on the held-out test split
