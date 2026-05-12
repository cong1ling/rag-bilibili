package com.example.ragcsdn.service.session;

import com.example.ragcsdn.dto.request.CreateSessionRequest;
import com.example.ragcsdn.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionCreationPolicyTest {

    @Test
    void createSingleArticleSessionRequiresArticleId() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setSessionType("SINGLE_ARTICLE");

        SessionCreationPolicy policy = new SessionCreationPolicy(null);

        assertThrows(BusinessException.class, () -> policy.validateAndNormalize(request, 1L));
    }
}
