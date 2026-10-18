package com.trading.api.service;

import com.trading.api.dto.AccountResponse;
import com.trading.api.dto.BalanceResponse;
import com.trading.api.dto.PositionResponse;

import java.util.List;

public interface AccountService {

    AccountResponse getAccount(Long accountId, Long tokenAccountId);

    BalanceResponse getBalance(Long accountId, Long tokenAccountId);

    List<PositionResponse> getPositions(Long accountId, Long tokenAccountId);
}
