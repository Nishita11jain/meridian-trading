package com.trading.api.service;

import com.trading.api.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseDuplicateOrderCheckerTest {

    @Mock
    private OrderMapper orderMapper;

    @Test
    void isDuplicateDelegatesToOrderMapper() {
        DatabaseDuplicateOrderChecker checker = new DatabaseDuplicateOrderChecker(orderMapper);
        when(orderMapper.existsByIdempotencyKey("idem-key-001")).thenReturn(true);

        boolean duplicate = checker.isDuplicate("idem-key-001");

        assertTrue(duplicate);
        verify(orderMapper).existsByIdempotencyKey("idem-key-001");
    }
}