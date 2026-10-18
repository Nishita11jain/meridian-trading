import duckdb

con = duckdb.connect('data/analytics.duckdb')

print('--- fact_trades by status ---')
print(con.execute('SELECT status, COUNT(*) FROM fact_trades GROUP BY status').fetchall())

print('--- dead letters, if any ---')
print(con.execute('SELECT reason, COUNT(*) FROM dead_letter_trades GROUP BY reason').fetchall())

print('--- dims populated ---')
print('accounts:', con.execute('SELECT COUNT(*) FROM dim_account').fetchone())
print('instruments:', con.execute('SELECT COUNT(*) FROM dim_instrument').fetchone())
print('dates:', con.execute('SELECT COUNT(*) FROM dim_date').fetchone())