package com.trading.api.extensions.strategies;

import com.trading.api.mapper.InstrumentMapper;
import com.trading.domain.Instrument;
import com.trading.domain.exception.AccountNotActiveException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Create, list and enable/disable a customer's strategies. Evaluating and firing them is
 * {@link StrategyExecutionService}'s job, driven by {@link StrategyMarketDataConsumer} -- this class
 * is the CRUD surface a customer's browser talks to.
 */
@Service
public class StrategiesService {

    private static final Set<String> SIDES = Set.of("BUY", "SELL");
    private static final Set<String> TYPES = Set.of("CROSSOVER", "BOLLINGER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final StrategiesMapper strategiesMapper;
    private final StrategyExecutionMapper executionMapper;
    private final InstrumentMapper instrumentMapper;
    private final int maxStrategiesPerAccount;

    public StrategiesService(
            StrategiesMapper strategiesMapper,
            StrategyExecutionMapper executionMapper,
            InstrumentMapper instrumentMapper,
            @Value("${strategies.max-per-account:10}") int maxStrategiesPerAccount) {
        this.strategiesMapper = strategiesMapper;
        this.executionMapper = executionMapper;
        this.instrumentMapper = instrumentMapper;
        this.maxStrategiesPerAccount = maxStrategiesPerAccount;
    }

    public List<StrategyResponse> list(Long accountId, Long tokenAccountId) {
        requireOwnership(accountId, tokenAccountId);
        return strategiesMapper.findByAccount(accountId).stream().map(StrategyResponse::from).toList();
    }

    public StrategyResponse create(Long accountId, Long tokenAccountId, StrategyRequest request) {
        requireOwnership(accountId, tokenAccountId);

        if (strategiesMapper.countForAccount(accountId) >= maxStrategiesPerAccount) {
            throw new StrategyLimitException(
                    "This account already holds the maximum of " + maxStrategiesPerAccount + " strategies.");
        }

        String symbol = request.symbol() == null ? null : request.symbol().trim().toUpperCase();
        Instrument instrument = symbol == null ? null : instrumentMapper.findBySymbol(symbol);
        if (instrument == null || !instrument.isTradable()) {
            throw new InvalidStrategyException("symbol must be a tradable instrument: " + request.symbol());
        }

        String side = request.side() == null ? null : request.side().trim().toUpperCase();
        if (!SIDES.contains(side)) {
            throw new InvalidStrategyException("side must be one of " + SIDES);
        }

        String type = request.strategyType() == null ? null : request.strategyType().trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new InvalidStrategyException("strategyType must be one of " + TYPES);
        }

        StrategyRow row = new StrategyRow();
        row.setAccountId(accountId);
        row.setSymbol(symbol);
        row.setSide(side);
        row.setStrategyType(type);
        row.setOrderQuantity(request.orderQuantity());
        row.setMaxSpend(request.maxSpend());
        row.setMaxPositionSize(request.maxPositionSize());
        row.setMaxConsecutiveFailures(request.maxConsecutiveFailures());

        if ("CROSSOVER".equals(type)) {
            if (request.fastWindow() == null || request.slowWindow() == null
                    || request.fastWindow() <= 0 || request.slowWindow() <= 0) {
                throw new InvalidStrategyException("fastWindow and slowWindow are required and must be positive for CROSSOVER");
            }
            if (request.fastWindow() >= request.slowWindow()) {
                throw new InvalidStrategyException("fastWindow must be smaller than slowWindow");
            }
            row.setFastWindow(request.fastWindow());
            row.setSlowWindow(request.slowWindow());
        } else {
            if (request.bandPeriod() == null || request.bandPeriod() <= 0
                    || request.bandMultiplier() == null || request.bandMultiplier().signum() <= 0) {
                throw new InvalidStrategyException("bandPeriod and bandMultiplier are required and must be positive for BOLLINGER");
            }
            row.setBandPeriod(request.bandPeriod());
            row.setBandMultiplier(request.bandMultiplier());
        }

        strategiesMapper.insert(row);
        return StrategyResponse.from(strategiesMapper.findById(row.getId()));
    }

    public StrategyResponse updateStatus(Long accountId, Long tokenAccountId, Long strategyId,
                                          UpdateStrategyStatusRequest request) {
        requireOwnership(accountId, tokenAccountId);
        StrategyRow row = requireOwnStrategy(accountId, strategyId);

        String status = request.status() == null ? null : request.status().trim().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new InvalidStrategyException("status must be one of " + STATUSES);
        }

        // Re-enabling is a fresh start: a strategy the circuit breaker disabled should not
        // immediately re-trip on its very next evaluation because the old count is still there.
        strategiesMapper.updateStatus(strategyId, status, "ACTIVE".equals(status) ? null : row.getDisabledReason());
        if ("ACTIVE".equals(status)) {
            strategiesMapper.resetFailures(strategyId);
        }
        return StrategyResponse.from(strategiesMapper.findById(strategyId));
    }

    public List<StrategyExecutionResponse> log(Long accountId, Long tokenAccountId, Long strategyId, int limit) {
        requireOwnership(accountId, tokenAccountId);
        requireOwnStrategy(accountId, strategyId);
        return executionMapper.findByStrategy(strategyId, limit).stream().map(StrategyExecutionResponse::from).toList();
    }

    private StrategyRow requireOwnStrategy(Long accountId, Long strategyId) {
        StrategyRow row = strategiesMapper.findById(strategyId);
        if (row == null || !row.getAccountId().equals(accountId)) {
            throw new StrategyNotFoundException("No strategy " + strategyId + " for this account.");
        }
        return row;
    }

    private void requireOwnership(Long accountId, Long tokenAccountId) {
        if (accountId == null || !accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
    }
}
