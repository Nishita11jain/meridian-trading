def transform_data(data):

    orders = data["orders"].copy()
    positions = data["positions"].copy()

    # Convert timestamp
    orders["created_on"] = orders["created_on"].astype(
        "datetime64[ns]"
    )

    # Trading value
    orders["trading_value"] = (
        orders["quantity"] * orders["price"]
    )

    # Portfolio exposure
    positions["exposure"] = (
        positions["quantity"] * positions["average_cost"]
    )

    data["orders"] = orders
    data["positions"] = positions

    return data