package com.trading.api.service;

import com.trading.api.dto.AccountResponse;
import com.trading.api.dto.BalanceResponse;
import com.trading.api.dto.PositionResponse;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.PositionRow;
import com.trading.domain.AccountStatus;
import com.trading.domain.Account;
import com.trading.domain.Position;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;

    public AccountServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
    }

    @Override
    public AccountResponse getAccount(Long accountId, Long tokenAccountId) {
        Account account = requireAccount(accountId, tokenAccountId);
        java.time.Instant lastUpdated = accountMapper.findLastUpdated(accountId);
        return new AccountResponse(
                account.getAccountId(),
                account.getAccountReference(),
                account.getHolderName(),
                account.getCashBalance().asBigDecimal(),
                account.getStatus(),
                account.getVersion(),
                lastUpdated);
    }

    @Override
    public BalanceResponse getBalance(Long accountId, Long tokenAccountId) {
        Account account = requireAccount(accountId, tokenAccountId);
        return new BalanceResponse(
                account.getAccountId(),
                account.getCashBalance().asBigDecimal(),
                "INR",
                java.time.Instant.now());
    }

    @Override
    public List<PositionResponse> getPositions(Long accountId, Long tokenAccountId) {
        requireAccount(accountId, tokenAccountId);
        List<Position> positions = positionMapper.findAllHeldByAccount(accountId).stream()
                .map(AccountServiceImpl::toDomain)
                .toList();
        return positions.stream()
                .map(p -> new PositionResponse(p.getAccountId(), p.getSymbol(), p.getQuantity(),
                        p.getAverageCost().asBigDecimal()))
                .toList();
    }

        private Account requireAccount(Long accountId, Long tokenAccountId) {
        // Ownership first: an account that is not the token's own is refused with the same
        // answer whether it exists or not, so response codes cannot be used to list real accounts.
        if (!accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
        AccountRow row = accountMapper.findById(accountId);
        if (row == null) {
            throw new AccountNotFoundException("account not found");
        }
        Account account = toDomain(row);
        // Calls the SAME domain method OrderPlacementService's rule 2
        // also calls -- eliminates the duplicated status comparison
        // without removing the check reads genuinely need, since a read
        // never goes through the domain's own rule 2 at all.
        if (!account.isActive()) {
            throw new AccountNotActiveException("account is not active");
        }
        // Same message a genuinely suspended account gets -- deliberately,
        // so a caller can't tell "wrong token" apart from "not active".
        if (!accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
        return account;
    }

    private static Account toDomain(AccountRow account) {
        return new Account(
                account.getId(),
                account.getAccountId(),
                account.getHolderName(),
                account.getCashBalance(),
                account.getStatus(),
                account.getVersion());
    }

    private static Position toDomain(PositionRow position) {
        return new Position(
                position.getAccountId(),
                position.getSymbol(),
                position.getQuantity(),
                position.getAverageCost(),
                position.getVersion());
    }
}