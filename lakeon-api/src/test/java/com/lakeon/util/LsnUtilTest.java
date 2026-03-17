package com.lakeon.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LsnUtilTest {

    @Test
    @DisplayName("parse_simpleLsn_returnsCorrectLong")
    void parse_simpleLsn_returnsCorrectLong() {
        // "0/1A2B3C0" -> segment=0, offset=0x1A2B3C0 = 27542464
        long result = LsnUtil.parse("0/1A2B3C0");
        assertThat(result).isEqualTo(0x1A2B3C0L);
    }

    @Test
    @DisplayName("parse_zeroLsn_returnsZero")
    void parse_zeroLsn_returnsZero() {
        long result = LsnUtil.parse("0/0");
        assertThat(result).isEqualTo(0L);
    }

    @Test
    @DisplayName("parse_largeLsn_handlesHighSegment")
    void parse_largeLsn_handlesHighSegment() {
        // "1/0" -> segment=1, offset=0 -> (1L << 32)
        long result = LsnUtil.parse("1/0");
        assertThat(result).isEqualTo(1L << 32);
    }

    @Test
    @DisplayName("format_roundTrip")
    void format_roundTrip() {
        String original = "0/1A2B3C0";
        long parsed = LsnUtil.parse(original);
        String formatted = LsnUtil.format(parsed);
        assertThat(formatted).isEqualToIgnoringCase(original);
    }

    @Test
    @DisplayName("parse_nullInput_throwsException")
    void parse_nullInput_throwsException() {
        assertThatThrownBy(() -> LsnUtil.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse_invalidFormat_throwsException")
    void parse_invalidFormat_throwsException() {
        assertThatThrownBy(() -> LsnUtil.parse("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
