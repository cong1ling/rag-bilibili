package com.example.ragcsdn.service.user;

import com.example.ragcsdn.dto.response.UserResponse;
import com.example.ragcsdn.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserResponseAssemblerTest {

    @Test
    void toResponseShouldMapSessionFlagsAndFormattedTimestamps() {
        User user = new User();
        user.setId(7L);
        user.setUsername("tester");
        user.setCreateTime(LocalDateTime.of(2026, 5, 12, 10, 30, 0));
        user.setCsdnCookieEncrypted("encrypted-cookie");
        user.setCsdnCookieUpdateTime(LocalDateTime.of(2026, 5, 12, 11, 45, 0));

        UserResponseAssembler assembler = new UserResponseAssembler();
        UserResponse response = assembler.toResponse(user);

        assertEquals(7L, response.getId());
        assertEquals("tester", response.getUsername());
        assertEquals("2026-05-12 10:30:00", response.getCreateTime());
        assertTrue(response.getHasCsdnSession());
        assertEquals("2026-05-12 11:45:00", response.getCsdnSessionUpdateTime());
    }

    @Test
    void toResponseShouldMarkMissingSessionAsFalse() {
        User user = new User();
        user.setId(8L);
        user.setUsername("tester-2");
        user.setCreateTime(LocalDateTime.of(2026, 5, 12, 12, 0, 0));

        UserResponseAssembler assembler = new UserResponseAssembler();
        UserResponse response = assembler.toResponse(user);

        assertFalse(response.getHasCsdnSession());
    }
}
