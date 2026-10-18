from .extract_seed import extract_data
from .transform_seed import transform_data
from .load_seed import load_to_duckdb


def run():

    print("Extracting...")
    data = extract_data()


    print("Transforming...")
    data = transform_data(data)


    print("Loading to DuckDB...")
    load_to_duckdb(data)


    print("ETL Completed")


if __name__ == "__main__":
    run()