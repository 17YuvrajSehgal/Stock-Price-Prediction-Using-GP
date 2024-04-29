import yfinance as yf
from datetime import datetime
# Define the ticker symbol for the stock you want to fetch data for
ticker_symbol = 'AAPL'

# Define the start and end dates for the historical data
start_date = '2020-01-01'
end_date = datetime.now().strftime('%Y-%m-%d')

# Fetch the historical data
stock_data = yf.download(ticker_symbol, start=start_date, end=end_date)
file_path = f'C:/workplace/Stock-Price-Prediction-Using-GP/src/main/data/{ticker_symbol}.csv'

stock_data.to_csv(file_path)