package com.example.ragcsdn.service.user;

import com.example.ragcsdn.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsdnCookieNormalizerTest {

    @Test
    void normalizeShouldStripCookiePrefixAndNormalizeSeparators() {
        CsdnCookieNormalizer normalizer = new CsdnCookieNormalizer();

        assertEquals("a=1; b=2", normalizer.normalize("Cookie: a=1;\r\nb=2"));
    }

    @Test
    void normalizeShouldRejectMissingKeyValueShape() {
        CsdnCookieNormalizer normalizer = new CsdnCookieNormalizer();

        assertThrows(BusinessException.class, () -> normalizer.normalize("just-text"));
    }
}
