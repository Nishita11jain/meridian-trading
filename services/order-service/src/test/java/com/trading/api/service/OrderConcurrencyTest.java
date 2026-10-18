package com.trading.api.service;

import com.trading.api.dto.ErrorResponse;
import com.trading.api.dto.OrderResponse;
import com.trading.api.exception.GlobalExceptionHandler;
import com.trading.api.mapper.AccountMapper;
import com.trading.api.mapper.InstrumentMapper;
import com.trading.api.mapper.OrderMapper;
import com.trading.api.mapper.PositionMapper;
import com.trading.api.mapper.row.AccountRow;
import com.trading.api.mapper.row.OrderHistoryRow;
import com.trading.domain.AccountStatus;
import com.trading.domain.Instrument;
import com.trading.domain.Money;
import com.trading.domain.OrderPlacementService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import com.trading.domain.exception.DomainException;
import com.trading.domain.exception.DuplicateOrderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency evidence for the 409 paths. Each test races real threads through
 * OrderServiceImpl and asserts that exactly one caller wins and the others are
 * refused with ORD-409, which the API answers as HTTP 409.
 *
 * The database is replaced by an in-memory stand-in that keeps the same
 * guarantee the real one gives: a unique idempotency key, and a cancel that is
 * conditional on the order still being NEW (WHERE id = ? AND status = 'NEW').
 * The barrier makes every thread pass the read-only check before any of them
 * writes, which is the interleaving a read-then-write check cannot survive on
 * its own -- so what these tests prove is that the write-side guard holds.
 */
@ExtendWith(MockitoExtension.class)
class OrderConcurrencyTest {

	private static final int THREADS = 8;
	private static final long WAIT_SECONDS = 10;

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

	private ExecutorService pool;

	@BeforeEach
	void setUp() {
		pool = Executors.newFixedThreadPool(THREADS);
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	@Test
	void concurrentOrdersWithTheSameIdempotencyKeyYieldExactlyOneAcceptedAndTheRestOrd409() throws Exception {
		CyclicBarrier allPassedRule8 = new CyclicBarrier(THREADS);
		// Rule 8's read-only check sees "unused" for every thread: all of them get past it
		// before any insert happens, so only the unique constraint can stop the duplicates.
		OrderServiceImpl service = placementService(key -> {
			awaitQuietly(allPassedRule8);
			return false;
		});
		stubAccountAndInstrument();
		Set<String> uniqueIdempotencyKeys = ConcurrentHashMap.newKeySet();
		stubInsertWithUniqueIdempotencyKey(uniqueIdempotencyKeys);

		List<Result<OrderResponse>> results = raceAll(THREADS,
				() -> service.placeOrder(request("same-key-0001"), 1L));

		long accepted = results.stream().filter(r -> r.value != null).count();
		List<Throwable> refused = results.stream().filter(r -> r.error != null).map(r -> r.error).toList();

		assertEquals(1, accepted, "exactly one of the concurrent requests may be accepted");
		assertEquals(THREADS - 1, refused.size());
		for (Throwable error : refused) {
			assertTrue(error instanceof DuplicateOrderException, "unexpected failure: " + error);
			assertEquals("ORD-409", ((DomainException) error).code());
			assertConflict(error);
		}
		assertEquals(1, uniqueIdempotencyKeys.size());
		verify(orderMapper, times(THREADS)).insert(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
		verify(orderEventPublisher, times(1)).publishOrderPlaced(any());
	}

	@Test
	void concurrentOrdersWithDifferentKeysAreAllAcceptedNoFalseConflicts() throws Exception {
		OrderServiceImpl service = placementService(key -> false);
		stubAccountAndInstrument();
		stubInsertWithUniqueIdempotencyKey(ConcurrentHashMap.newKeySet());

		List<Result<OrderResponse>> results = new ArrayList<>();
		List<Callable<OrderResponse>> calls = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			String key = "distinct-key-" + i;
			calls.add(() -> service.placeOrder(request(key), 1L));
		}
		for (Future<OrderResponse> future : invokeTogether(calls)) {
			results.add(Result.of(future));
		}

		assertTrue(results.stream().allMatch(r -> r.value != null),
				"different idempotency keys must never conflict: " + results.stream().map(r -> r.error).toList());
		verify(orderEventPublisher, times(THREADS)).publishOrderPlaced(any());
	}

	@Test
	void concurrentCancelsOfTheSameOrderYieldExactlyOneCancelAndTheRestOrd409() throws Exception {
		UUID orderId = UUID.randomUUID();
		CyclicBarrier allReadNew = new CyclicBarrier(THREADS);
		AtomicBoolean cancelled = new AtomicBoolean(false);
		OrderServiceImpl service = placementService(key -> false);

		// Every thread reads the order as NEW before any of them cancels it.
		when(orderMapper.findById(orderId)).thenAnswer(invocation -> {
			OrderHistoryRow row = orderRow(orderId, OrderStatus.NEW);
			awaitQuietly(allReadNew);
			return row;
		});
		// The conditional UPDATE ... WHERE status = 'NEW': only one caller changes the row.
		when(orderMapper.cancelIfNew(orderId)).thenAnswer(invocation -> cancelled.compareAndSet(false, true) ? 1 : 0);

		List<Result<OrderResponse>> results = raceAll(THREADS, () -> service.cancelOrder(orderId, 1L));

		assertEquals(1, results.stream().filter(r -> r.value != null).count(),
				"exactly one cancel may succeed");
		results.stream().filter(r -> r.error != null).forEach(r -> {
			assertTrue(r.error instanceof DuplicateOrderException, "unexpected failure: " + r.error);
			assertEquals("ORD-409", ((DomainException) r.error).code());
			assertEquals("Order is not cancellable", r.error.getMessage());
			assertConflict(r.error);
		});
		verify(orderMapper, times(THREADS)).cancelIfNew(orderId);
		verify(orderEventPublisher, times(1)).publishOrderCancelled(any(), any());
	}

	@Test
	void cancelRacingTheExecutorsFillLeavesExactlyOneWinnerAndTheLoserGetsOrd409() throws Exception {
		// The customer cancels while the Trade Executor fills the same NEW order. Both writes
		// are conditional on status = 'NEW', so whichever lands first wins and the other is
		// refused. Repeated to cover both orderings.
		for (int round = 0; round < 50; round++) {
			org.mockito.Mockito.reset(orderMapper, orderEventPublisher);

			UUID orderId = UUID.randomUUID();
			AtomicReference<OrderStatus> status = new AtomicReference<>(OrderStatus.NEW);
			OrderServiceImpl service = placementService(key -> false);

			// Lenient: when the fill lands first the service refuses at its pre-check and never
			// reaches cancelIfNew, so that stub is legitimately unused in about half the rounds.
			org.mockito.Mockito.lenient().when(orderMapper.findById(orderId))
					.thenAnswer(invocation -> orderRow(orderId, status.get()));
			org.mockito.Mockito.lenient().when(orderMapper.cancelIfNew(orderId))
					.thenAnswer(invocation -> status.compareAndSet(OrderStatus.NEW, OrderStatus.CANCELLED) ? 1 : 0);

			CountDownLatch start = new CountDownLatch(1);
			Future<Object> cancel = pool.submit(() -> {
				start.await();
				try {
					return service.cancelOrder(orderId, 1L);
				} catch (Throwable error) {
					return error;
				}
			});
			Future<Boolean> executorFill = pool.submit(() -> {
				start.await();
				// What OrderMapper.markFilled does: WHERE id = ? AND status = 'NEW'.
				return status.compareAndSet(OrderStatus.NEW, OrderStatus.FILLED);
			});
			start.countDown();

			Object cancelOutcome = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
			boolean executorWon = executorFill.get(WAIT_SECONDS, TimeUnit.SECONDS);

			if (executorWon) {
				assertEquals(OrderStatus.FILLED, status.get());
				assertTrue(cancelOutcome instanceof DuplicateOrderException,
						"the cancel lost to the fill and must be ORD-409, got " + cancelOutcome);
				assertEquals("ORD-409", ((DomainException) cancelOutcome).code());
				verify(orderEventPublisher, never()).publishOrderCancelled(any(), any());
			} else {
				assertEquals(OrderStatus.CANCELLED, status.get());
				assertTrue(cancelOutcome instanceof OrderResponse,
						"the cancel won and must succeed, got " + cancelOutcome);
				verify(orderEventPublisher, times(1)).publishOrderCancelled(any(), any());
			}
		}
	}

	// ---- helpers -------------------------------------------------------------------------------

	private OrderServiceImpl placementService(com.trading.domain.DuplicateOrderChecker checker) {
		return new OrderServiceImpl(accountMapper, instrumentMapper, positionMapper, orderMapper,
				new OrderPlacementService(checker), orderEventPublisher, rejectedOrderRecorder, org.mockito.Mockito.mock(TradingHours.class), org.mockito.Mockito.mock(com.trading.api.fx.OrderFxService.class));
	}

	private void stubAccountAndInstrument() {
		AccountRow account = new AccountRow();
		account.setId(1L);
		account.setAccountId("ACC-REF-1");
		account.setHolderName("Holder");
		account.setCashBalance(Money.of("1000000.00"));
		account.setStatus(AccountStatus.ACTIVE);
		account.setVersion(1);
		when(accountMapper.findByIdForUpdate(1L)).thenReturn(account);
		when(instrumentMapper.findBySymbol("INFY.NS"))
				.thenReturn(new Instrument("INFY.NS", "Infosys", "EQUITY", "INR", true));
	}

	/** Stand-in for the unique constraint uq_orders_idempotency_key: the atomic add is the constraint. */
	private void stubInsertWithUniqueIdempotencyKey(Set<String> keys) {
		when(orderMapper.insert(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> {
					String idempotencyKey = invocation.getArgument(8);
					if (!keys.add(idempotencyKey)) {
						throw new DuplicateKeyException("duplicate key value violates unique constraint "
								+ "\"uq_orders_idempotency_key\"");
					}
					return 1;
				});
	}

	private static PlaceOrderRequest request(String idempotencyKey) {
		return new PlaceOrderRequest(1L, "INFY.NS", OrderSide.BUY, 10, new BigDecimal("100.00"), idempotencyKey);
	}

	private static OrderHistoryRow orderRow(UUID orderId, OrderStatus status) {
		OrderHistoryRow row = new OrderHistoryRow();
		row.setOrderId("ORD-" + orderId);
		row.setAccountId(1L);
		row.setSymbol("INFY.NS");
		row.setSide(OrderSide.BUY);
		row.setQuantity(10);
		row.setPrice(new BigDecimal("100.00"));
		row.setStatus(status);
		row.setIdempotencyKey("idem-" + orderId);
		row.setCreatedOn(Instant.now());
		return row;
	}

	/** The HTTP answer the API gives for this failure: 409 with the ORD-409 body. */
	private static void assertConflict(Throwable error) {
		ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler().handleDomainException((DomainException) error);
		assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
		assertEquals("ORD-409", response.getBody().errorCode());
	}

	private <T> List<Result<T>> raceAll(int threads, Callable<T> call) throws Exception {
		List<Callable<T>> calls = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			calls.add(call);
		}
		List<Result<T>> results = new ArrayList<>();
		for (Future<T> future : invokeTogether(calls)) {
			results.add(Result.of(future));
		}
		return results;
	}

	/** Releases every call at the same instant, so the threads genuinely overlap. */
	private <T> List<Future<T>> invokeTogether(List<Callable<T>> calls) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		for (Callable<T> call : calls) {
			futures.add(pool.submit(() -> {
				start.await();
				return call.call();
			}));
		}
		start.countDown();
		for (Future<T> future : futures) {
			try {
				future.get(WAIT_SECONDS, TimeUnit.SECONDS);
			} catch (java.util.concurrent.ExecutionException ignored) {
				// inspected by the caller through Result.of
			}
		}
		return futures;
	}

	private static void awaitQuietly(CyclicBarrier barrier) {
		try {
			barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new IllegalStateException("the threads did not all reach the barrier", exception);
		}
	}

	private record Result<T>(T value, Throwable error) {

		static <T> Result<T> of(Future<T> future) {
			try {
				return new Result<>(future.get(), null);
			} catch (java.util.concurrent.ExecutionException exception) {
				return new Result<>(null, exception.getCause());
			} catch (Exception exception) {
				return new Result<>(null, exception);
			}
		}
	}
}
