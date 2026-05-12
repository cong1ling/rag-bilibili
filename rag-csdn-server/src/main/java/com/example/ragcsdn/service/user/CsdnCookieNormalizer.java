package com.example.ragcsdn.service.user;

import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class CsdnCookieNormalizer {

    public String normalize(String rawCookie) {
        if (rawCookie == null || rawCookie.isBlank()) {
            throw new BusinessException(ErrorCode.CSDN_SESSION_INVALID);
        }

        String normalized = rawCookie.trim();
        if (normalized.regionMatches(true, 0, "Cookie:", 0, "Cookie:".length())) {
            normalized = normalized.substring("Cookie:".length()).trim();
        }

        normalized = normalized.replace("\r\n", "\n")
                .replace('\n', ';')
                .replace('\r', ';')
                .replaceAll(";{2,}", ";")
                .replaceAll("\\s*;\\s*", "; ")
                .trim();

        if (normalized.isBlank() || !normalized.contains("=")) {
            throw new BusinessException(ErrorCode.CSDN_SESSION_INVALID);
        }
        return normalized;
    }
}
