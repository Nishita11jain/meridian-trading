package com.trading.api.service;

import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.domain.AccountStatus;
import com.trading.domain.Instrument;
import com.trading.domain.Money;
import com.trading.domain.OrderPlacementService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceAfterCommitPublishingTest {

	@Mock
	private AccountMapper accountMapper;

	@Mock
	private InstrumentMapper instrumentMapper;

	@Mock
	private PositionMapper positionMapper;

	@Mock
	private OrderMapper orderMapper;

	@Mock
	private OrderEventPublisher orderEventPublisher;

	@Mock
	private RejectedOrderRecorder rejectedOrderRecorder;

	private OrderServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new OrderServiceImpl(
				accountMapper,
				instrumentMapper,
				positionMapper,
				orderMapper,
				new OrderPlacementService(key -> false),
				orderEventPublisher,
				rejectedOrderRecorder,
				org.mockito.Mockito.mock(TradingHours.class), org.mockito.Mockito.mock(com.trading.api.fx.OrderFxService.class));
	}

	@AfterEach
	void tearDown() {
		TransactionSynchronizationManager.setActualTransactionActive(false);

		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void publishOrderPlacedHappensOnlyAfterCommit() {
		when(accountMapper.findByIdForUpdate(1L)).thenReturn(account());
		when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(tradableInstrument());
		when(positionMapper.findByAccountAndSymbol(1L, "INFY.NS")).thenReturn(null);

		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();

		service.placeOrder(
				new PlaceOrderRequest(
						1L,
						"INFY.NS",
						OrderSide.BUY,
						10,
						new BigDecimal("100.00"),
						"idem-key-after-commit"
				),
				1L
		);

		verify(orderEventPublisher, never()).publishOrderPlaced(any());

		TransactionSynchronization synchronization =
				TransactionSynchronizationManager.getSynchronizations()
						.iterator()
						.next();

		synchronization.afterCommit();

		verify(orderEventPublisher).publishOrderPlaced(any());
	}

	private static AccountRow account() {
		AccountRow account = new AccountRow();
		account.setId(1L);
		account.setAccountId("ACC-REF-1");
		account.setHolderName("Holder");
		account.setCashBalance(Money.of("10000.00"));
		account.setStatus(AccountStatus.ACTIVE);
		account.setVersion(4);
		return account;
	}

	private static Instrument tradableInstrument() {
		return new Instrument("INFY.NS", "Infosys", "EQUITY", "INR", true);
	}
}

