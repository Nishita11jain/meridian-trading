-- Schema snapshot of the public schema after migrations/001..022 (pg_dump --schema-only).
-- To create a database: psql -d <db> -f schema.sql && psql -d <db> -f seed-data.sql
-- migrations/ remain the source of truth; regenerate this file after adding one.
--
-- PostgreSQL database dump
--


-- Dumped from database version 18.4
-- Dumped by pg_dump version 18.4


--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--





--
-- Name: account_transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_transactions (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    transaction_type character varying(30) NOT NULL,
    amount numeric(18,2) NOT NULL,
    reference_type character varying(30),
    reference_id character varying(100),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_account_transactions_amount_nonzero CHECK ((amount <> (0)::numeric)),
    CONSTRAINT ck_account_transactions_type CHECK (((transaction_type)::text = ANY ((ARRAY['DEPOSIT'::character varying, 'WITHDRAWAL'::character varying, 'BUY'::character varying, 'SELL'::character varying])::text[])))
);


--
-- Name: account_transactions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.account_transactions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.account_transactions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.accounts (
    id bigint NOT NULL,
    account_id character varying(32) NOT NULL,
    holder_name character varying(255) NOT NULL,
    cash_balance numeric(18,2) NOT NULL,
    status character varying(20) NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    last_updated timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_accounts_cash_balance_non_negative CHECK ((cash_balance >= (0)::numeric)),
    CONSTRAINT ck_accounts_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: accounts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.accounts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.accounts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: candle_cache; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.candle_cache (
    symbol character varying(20) NOT NULL,
    "interval" character varying(10) DEFAULT '1d'::character varying NOT NULL,
    currency character(3),
    candles_json text NOT NULL,
    fetched_at timestamp without time zone DEFAULT now() NOT NULL,
    expires_at timestamp without time zone NOT NULL
);


--
-- Name: customer_preferences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_preferences (
    account_id bigint NOT NULL,
    default_account_id bigint NOT NULL,
    notification_channel character varying(10) DEFAULT 'IN_APP'::character varying NOT NULL,
    contact_email character varying(320),
    contact_phone character varying(32),
    display_currency character(3) DEFAULT 'INR'::bpchar NOT NULL,
    theme character varying(10) DEFAULT 'SYSTEM'::character varying NOT NULL,
    updated_on timestamp without time zone DEFAULT now() NOT NULL,
    avatar_key character varying(20) DEFAULT 'avatar_01'::character varying NOT NULL,
    CONSTRAINT ck_customer_preferences_avatar CHECK (((avatar_key)::text = ANY ((ARRAY['avatar_01'::character varying, 'avatar_02'::character varying, 'avatar_03'::character varying, 'avatar_04'::character varying, 'avatar_05'::character varying, 'avatar_06'::character varying, 'avatar_07'::character varying, 'avatar_08'::character varying])::text[]))),
    CONSTRAINT ck_customer_preferences_channel CHECK (((notification_channel)::text = ANY ((ARRAY['IN_APP'::character varying, 'EMAIL'::character varying, 'SMS'::character varying, 'PUSH'::character varying])::text[]))),
    CONSTRAINT ck_customer_preferences_currency CHECK ((display_currency ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT ck_customer_preferences_theme CHECK (((theme)::text = ANY ((ARRAY['LIGHT'::character varying, 'DARK'::character varying, 'SYSTEM'::character varying])::text[])))
);


--
-- Name: fx_rates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fx_rates (
    pair character varying(10) NOT NULL,
    rate_date date NOT NULL,
    rate numeric(18,6) NOT NULL,
    quote_as_of timestamp without time zone NOT NULL,
    fetched_on timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_fx_rates_pair CHECK (((pair)::text = ANY ((ARRAY['USDINR'::character varying, 'INRUSD'::character varying])::text[]))),
    CONSTRAINT ck_fx_rates_rate_positive CHECK ((rate > (0)::numeric))
);


--
-- Name: instruments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.instruments (
    symbol character varying(20) NOT NULL,
    instrument_name character varying(255) NOT NULL,
    asset_class character varying(20) NOT NULL,
    currency character(3) NOT NULL,
    tradable boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_instruments_asset_class CHECK (((asset_class)::text = ANY ((ARRAY['EQUITY'::character varying, 'ETF'::character varying, 'FX'::character varying, 'CRYPTO'::character varying, 'BOND'::character varying])::text[]))),
    CONSTRAINT ck_instruments_currency CHECK ((currency ~ '^[A-Z]{3}$'::text))
);


--
-- Name: market_holidays; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.market_holidays (
    market character varying(10) NOT NULL,
    holiday_date date NOT NULL,
    name character varying(100) NOT NULL,
    created_on timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_market_holidays_market CHECK (((market)::text = ANY ((ARRAY['NSE'::character varying, 'BSE'::character varying, 'US'::character varying, 'FX'::character varying])::text[])))
);


--
-- Name: market_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.market_price (
    symbol character varying(20) NOT NULL,
    last_price numeric(18,4) NOT NULL,
    bid numeric(18,4),
    ask numeric(18,4),
    currency character(3) NOT NULL,
    change numeric(18,4),
    change_percent numeric(9,4),
    previous_close numeric(18,4),
    market_state character varying(10) DEFAULT 'unknown'::character varying NOT NULL,
    stale boolean DEFAULT false NOT NULL,
    quote_as_of timestamp without time zone NOT NULL,
    updated_on timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_market_price_currency CHECK ((currency ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT ck_market_price_market_state CHECK (((market_state)::text = ANY ((ARRAY['open'::character varying, 'closed'::character varying, 'pre'::character varying, 'post'::character varying, 'unknown'::character varying])::text[])))
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    account_id bigint NOT NULL,
    source_event_id uuid NOT NULL,
    type character varying(40) NOT NULL,
    channel character varying(10) NOT NULL,
    subject character varying(160) NOT NULL,
    message character varying(1000) NOT NULL,
    delivery_state character varying(10) DEFAULT 'SENT'::character varying NOT NULL,
    read_on timestamp without time zone,
    created_on timestamp without time zone DEFAULT now() NOT NULL,
    order_id uuid,
    alert_id bigint,
    symbol character varying(20),
    CONSTRAINT ck_notifications_channel CHECK (((channel)::text = ANY ((ARRAY['IN_APP'::character varying, 'EMAIL'::character varying, 'SMS'::character varying, 'PUSH'::character varying])::text[]))),
    CONSTRAINT ck_notifications_delivery_state CHECK (((delivery_state)::text = ANY ((ARRAY['QUEUED'::character varying, 'SENT'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orders (
    id uuid NOT NULL,
    account_id bigint NOT NULL,
    symbol character varying(20) NOT NULL,
    side character varying(4) NOT NULL,
    quantity integer NOT NULL,
    price numeric(18,2) NOT NULL,
    status character varying(20) NOT NULL,
    idempotency_key character varying(100) NOT NULL,
    created_on timestamp without time zone DEFAULT now() NOT NULL,
    executed_price numeric(18,2),
    filled_quantity integer,
    updated_on timestamp without time zone,
    rejection_reason character varying(255),
    executed_on timestamp without time zone,
    fx_rate numeric(18,6),
    CONSTRAINT ck_orders_executed_price_positive CHECK (((executed_price IS NULL) OR (executed_price > (0)::numeric))),
    CONSTRAINT ck_orders_filled_quantity_non_negative CHECK (((filled_quantity IS NULL) OR (filled_quantity >= 0))),
    CONSTRAINT ck_orders_fx_rate_positive CHECK (((fx_rate IS NULL) OR (fx_rate > (0)::numeric))),
    CONSTRAINT ck_orders_price_positive CHECK ((price > (0)::numeric)),
    CONSTRAINT ck_orders_quantity_positive CHECK ((quantity > 0)),
    CONSTRAINT ck_orders_side CHECK (((side)::text = ANY ((ARRAY['BUY'::character varying, 'SELL'::character varying])::text[]))),
    CONSTRAINT ck_orders_status CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'FILLED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: positions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.positions (
    account_id bigint NOT NULL,
    symbol character varying(20) NOT NULL,
    quantity integer NOT NULL,
    average_cost numeric(18,2) NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_positions_average_cost_non_negative CHECK ((average_cost >= (0)::numeric)),
    CONSTRAINT ck_positions_quantity_non_negative CHECK ((quantity >= 0))
);


--
-- Name: price_alert_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.price_alert_state (
    alert_id bigint NOT NULL,
    last_observed_price numeric(18,4),
    updated_on timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: price_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.price_alerts (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    symbol character varying(20) NOT NULL,
    direction character varying(5) NOT NULL,
    target_price numeric(18,4) NOT NULL,
    status character varying(10) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_on timestamp without time zone DEFAULT now() NOT NULL,
    triggered_on timestamp without time zone,
    triggered_price numeric(18,4),
    CONSTRAINT ck_price_alerts_direction CHECK (((direction)::text = ANY ((ARRAY['ABOVE'::character varying, 'BELOW'::character varying])::text[]))),
    CONSTRAINT ck_price_alerts_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'TRIGGERED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_price_alerts_target_price_positive CHECK ((target_price > (0)::numeric)),
    CONSTRAINT ck_price_alerts_triggered_fields CHECK (((((status)::text = 'TRIGGERED'::text) AND (triggered_on IS NOT NULL) AND (triggered_price IS NOT NULL)) OR ((status)::text <> 'TRIGGERED'::text)))
);


--
-- Name: price_alerts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.price_alerts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.price_alerts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: price_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.price_history (
    id bigint NOT NULL,
    symbol character varying(20) NOT NULL,
    price numeric(18,4) NOT NULL,
    bid numeric(18,4),
    ask numeric(18,4),
    quote_as_of timestamp without time zone NOT NULL,
    recorded_on timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: price_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.price_history ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.price_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: processed_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.processed_events (
    consumer_name character varying(40) NOT NULL,
    event_id uuid NOT NULL,
    processed_on timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: strategy_execution_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.strategy_execution_log (
    id bigint NOT NULL,
    strategy_id bigint NOT NULL,
    triggered_at timestamp without time zone DEFAULT now() NOT NULL,
    triggering_price numeric(18,4) NOT NULL,
    action character varying(20) NOT NULL,
    outcome character varying(10) NOT NULL,
    detail character varying(300),
    order_id uuid,
    CONSTRAINT ck_strategy_execution_log_outcome CHECK (((outcome)::text = ANY ((ARRAY['PLACED'::character varying, 'REJECTED'::character varying, 'SKIPPED'::character varying])::text[])))
);


--
-- Name: strategy_execution_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.strategy_execution_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: strategy_execution_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.strategy_execution_log_id_seq OWNED BY public.strategy_execution_log.id;


--
-- Name: trade_signal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trade_signal (
    symbol character varying(20) NOT NULL,
    direction character varying(4) NOT NULL,
    strength numeric(6,4) NOT NULL,
    rationale character varying(500) NOT NULL,
    computed_at timestamp without time zone DEFAULT now() NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    CONSTRAINT ck_trade_signal_direction CHECK (((direction)::text = ANY ((ARRAY['BUY'::character varying, 'SELL'::character varying, 'HOLD'::character varying])::text[])))
);


--
-- Name: trades; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trades (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    order_id uuid NOT NULL,
    quantity integer NOT NULL,
    execution_price numeric(18,2) NOT NULL,
    broker_reference character varying(100),
    executed_at timestamp without time zone DEFAULT now() NOT NULL,
    fx_rate numeric(18,6),
    CONSTRAINT ck_trades_execution_price_positive CHECK ((execution_price > (0)::numeric)),
    CONSTRAINT ck_trades_fx_rate_positive CHECK (((fx_rate IS NULL) OR (fx_rate > (0)::numeric))),
    CONSTRAINT ck_trades_quantity_positive CHECK ((quantity > 0))
);


--
-- Name: trading_strategy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trading_strategy (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    symbol character varying(20) NOT NULL,
    side character varying(4) NOT NULL,
    strategy_type character varying(10) NOT NULL,
    fast_window integer,
    slow_window integer,
    band_period integer,
    band_multiplier numeric(4,2),
    order_quantity integer NOT NULL,
    max_spend numeric(18,2) NOT NULL,
    max_position_size integer NOT NULL,
    max_consecutive_failures integer DEFAULT 3 NOT NULL,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    status character varying(10) DEFAULT 'ACTIVE'::character varying NOT NULL,
    disabled_reason character varying(200),
    created_on timestamp without time zone DEFAULT now() NOT NULL,
    updated_on timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_trading_strategy_max_failures CHECK ((max_consecutive_failures > 0)),
    CONSTRAINT ck_trading_strategy_max_position CHECK ((max_position_size > 0)),
    CONSTRAINT ck_trading_strategy_max_spend CHECK ((max_spend > (0)::numeric)),
    CONSTRAINT ck_trading_strategy_order_quantity CHECK ((order_quantity > 0)),
    CONSTRAINT ck_trading_strategy_side CHECK (((side)::text = ANY ((ARRAY['BUY'::character varying, 'SELL'::character varying])::text[]))),
    CONSTRAINT ck_trading_strategy_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT ck_trading_strategy_type CHECK (((strategy_type)::text = ANY ((ARRAY['CROSSOVER'::character varying, 'BOLLINGER'::character varying])::text[])))
);


--
-- Name: trading_strategy_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.trading_strategy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: trading_strategy_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.trading_strategy_id_seq OWNED BY public.trading_strategy.id;


--
-- Name: watchlist_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.watchlist_items (
    watchlist_id bigint NOT NULL,
    symbol character varying(20) NOT NULL,
    added_on timestamp without time zone DEFAULT now() NOT NULL,
    "position" integer,
    flag character varying(50)
);


--
-- Name: watchlists; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.watchlists (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    created_on timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: watchlists_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.watchlists ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.watchlists_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: strategy_execution_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.strategy_execution_log ALTER COLUMN id SET DEFAULT nextval('public.strategy_execution_log_id_seq'::regclass);


--
-- Name: trading_strategy id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_strategy ALTER COLUMN id SET DEFAULT nextval('public.trading_strategy_id_seq'::regclass);


--
-- Name: account_transactions pk_account_transactions; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_transactions
    ADD CONSTRAINT pk_account_transactions PRIMARY KEY (id);


--
-- Name: accounts pk_accounts; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT pk_accounts PRIMARY KEY (id);


--
-- Name: candle_cache pk_candle_cache; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.candle_cache
    ADD CONSTRAINT pk_candle_cache PRIMARY KEY (symbol);


--
-- Name: customer_preferences pk_customer_preferences; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_preferences
    ADD CONSTRAINT pk_customer_preferences PRIMARY KEY (account_id);


--
-- Name: fx_rates pk_fx_rates; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fx_rates
    ADD CONSTRAINT pk_fx_rates PRIMARY KEY (pair, rate_date);


--
-- Name: instruments pk_instruments; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.instruments
    ADD CONSTRAINT pk_instruments PRIMARY KEY (symbol);


--
-- Name: market_holidays pk_market_holidays; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_holidays
    ADD CONSTRAINT pk_market_holidays PRIMARY KEY (market, holiday_date);


--
-- Name: market_price pk_market_price; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_price
    ADD CONSTRAINT pk_market_price PRIMARY KEY (symbol);


--
-- Name: notifications pk_notifications; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT pk_notifications PRIMARY KEY (id);


--
-- Name: orders pk_orders; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT pk_orders PRIMARY KEY (id);


--
-- Name: positions pk_positions; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT pk_positions PRIMARY KEY (account_id, symbol);


--
-- Name: price_alert_state pk_price_alert_state; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_alert_state
    ADD CONSTRAINT pk_price_alert_state PRIMARY KEY (alert_id);


--
-- Name: price_alerts pk_price_alerts; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_alerts
    ADD CONSTRAINT pk_price_alerts PRIMARY KEY (id);


--
-- Name: price_history pk_price_history; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_history
    ADD CONSTRAINT pk_price_history PRIMARY KEY (id);


--
-- Name: processed_events pk_processed_events; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.processed_events
    ADD CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id);


--
-- Name: strategy_execution_log pk_strategy_execution_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.strategy_execution_log
    ADD CONSTRAINT pk_strategy_execution_log PRIMARY KEY (id);


--
-- Name: trade_signal pk_trade_signal; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trade_signal
    ADD CONSTRAINT pk_trade_signal PRIMARY KEY (symbol);


--
-- Name: trades pk_trades; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trades
    ADD CONSTRAINT pk_trades PRIMARY KEY (id);


--
-- Name: trading_strategy pk_trading_strategy; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_strategy
    ADD CONSTRAINT pk_trading_strategy PRIMARY KEY (id);


--
-- Name: watchlist_items pk_watchlist_items; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.watchlist_items
    ADD CONSTRAINT pk_watchlist_items PRIMARY KEY (watchlist_id, symbol);


--
-- Name: watchlists pk_watchlists; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.watchlists
    ADD CONSTRAINT pk_watchlists PRIMARY KEY (id);


--
-- Name: accounts uq_accounts_account_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT uq_accounts_account_id UNIQUE (account_id);


--
-- Name: notifications uq_notifications_source_event_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT uq_notifications_source_event_id UNIQUE (source_event_id);


--
-- Name: orders uq_orders_idempotency_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key);


--
-- Name: trades uq_trades_order_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trades
    ADD CONSTRAINT uq_trades_order_id UNIQUE (order_id);


--
-- Name: watchlists uq_watchlists_account_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.watchlists
    ADD CONSTRAINT uq_watchlists_account_name UNIQUE (account_id, name);


--
-- Name: ix_account_transactions_account_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_account_transactions_account_created ON public.account_transactions USING btree (account_id, created_at DESC);


--
-- Name: ix_account_transactions_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_account_transactions_reference ON public.account_transactions USING btree (reference_type, reference_id);


--
-- Name: ix_fx_rates_pair_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_fx_rates_pair_date ON public.fx_rates USING btree (pair, rate_date DESC);


--
-- Name: ix_notifications_account_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_notifications_account_created ON public.notifications USING btree (account_id, created_on DESC);


--
-- Name: ix_orders_account_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_orders_account_created ON public.orders USING btree (account_id, created_on DESC);


--
-- Name: ix_orders_account_open_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_orders_account_open_created ON public.orders USING btree (account_id, created_on DESC) WHERE ((status)::text = 'NEW'::text);


--
-- Name: ix_orders_created_on; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_orders_created_on ON public.orders USING btree (created_on);


--
-- Name: ix_price_alerts_symbol_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_price_alerts_symbol_active ON public.price_alerts USING btree (symbol) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: ix_price_history_symbol_quote_as_of; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_price_history_symbol_quote_as_of ON public.price_history USING btree (symbol, quote_as_of DESC);


--
-- Name: ix_strategy_execution_log_strategy; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_strategy_execution_log_strategy ON public.strategy_execution_log USING btree (strategy_id, triggered_at DESC);


--
-- Name: ix_trades_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trades_order_id ON public.trades USING btree (order_id);


--
-- Name: ix_trading_strategy_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trading_strategy_account ON public.trading_strategy USING btree (account_id);


--
-- Name: ix_trading_strategy_symbol_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trading_strategy_symbol_status ON public.trading_strategy USING btree (symbol, status);


--
-- Name: account_transactions fk_account_transactions_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_transactions
    ADD CONSTRAINT fk_account_transactions_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: customer_preferences fk_customer_preferences_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_preferences
    ADD CONSTRAINT fk_customer_preferences_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: customer_preferences fk_customer_preferences_default_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_preferences
    ADD CONSTRAINT fk_customer_preferences_default_account FOREIGN KEY (default_account_id) REFERENCES public.accounts(id);


--
-- Name: market_price fk_market_price_instrument; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_price
    ADD CONSTRAINT fk_market_price_instrument FOREIGN KEY (symbol) REFERENCES public.instruments(symbol);


--
-- Name: notifications fk_notifications_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT fk_notifications_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: orders fk_orders_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: orders fk_orders_instrument; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fk_orders_instrument FOREIGN KEY (symbol) REFERENCES public.instruments(symbol);


--
-- Name: positions fk_positions_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT fk_positions_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: positions fk_positions_instrument; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT fk_positions_instrument FOREIGN KEY (symbol) REFERENCES public.instruments(symbol);


--
-- Name: price_alert_state fk_price_alert_state_alert; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_alert_state
    ADD CONSTRAINT fk_price_alert_state_alert FOREIGN KEY (alert_id) REFERENCES public.price_alerts(id) ON DELETE CASCADE;


--
-- Name: price_alerts fk_price_alerts_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_alerts
    ADD CONSTRAINT fk_price_alerts_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: price_alerts fk_price_alerts_instrument; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_alerts
    ADD CONSTRAINT fk_price_alerts_instrument FOREIGN KEY (symbol) REFERENCES public.instruments(symbol);


--
-- Name: price_history fk_price_history_instrument; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_history
    ADD CONSTRAINT fk_price_history_instrument FOREIGN KEY (symbol) REFERENCES public.instruments(symbol);


--
-- Name: strategy_execution_log fk_strategy_execution_log_strategy; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.strategy_execution_log
    ADD CONSTRAINT fk_strategy_execution_log_strategy FOREIGN KEY (strategy_id) REFERENCES public.trading_strategy(id) ON DELETE CASCADE;


--
-- Name: trades fk_trades_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trades
    ADD CONSTRAINT fk_trades_order FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: trading_strategy fk_trading_strategy_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_strategy
    ADD CONSTRAINT fk_trading_strategy_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- Name: watchlist_items fk_watchlist_items_instrument; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.watchlist_items
    ADD CONSTRAINT fk_watchlist_items_instrument FOREIGN KEY (symbol) REFERENCES public.instruments(symbol);


--
-- Name: watchlist_items fk_watchlist_items_watchlist; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.watchlist_items
    ADD CONSTRAINT fk_watchlist_items_watchlist FOREIGN KEY (watchlist_id) REFERENCES public.watchlists(id);


--
-- Name: watchlists fk_watchlists_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.watchlists
    ADD CONSTRAINT fk_watchlists_account FOREIGN KEY (account_id) REFERENCES public.accounts(id);


--
-- PostgreSQL database dump complete
--


