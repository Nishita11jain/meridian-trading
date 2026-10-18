package com.trading.api.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentModificationDetectedExceptionTest {

    @Test
    void messageIsPreserved() {
        ConcurrentModificationDetectedException exception =
                new ConcurrentModificationDetectedException("concurrent update detected");

        assertThat(exception.getMessage()).isEqualTo("concurrent update detected");
    }
}
