package com.example.ragcsdn.service.user;

import com.example.ragcsdn.dto.response.UserResponse;
import com.example.ragcsdn.entity.User;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class UserResponseAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public UserResponse toResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setCreateTime(user.getCreateTime().format(FORMATTER));
        response.setHasCsdnSession(user.getCsdnCookieEncrypted() != null && !user.getCsdnCookieEncrypted().isBlank());
        if (user.getCsdnCookieUpdateTime() != null) {
            response.setCsdnSessionUpdateTime(user.getCsdnCookieUpdateTime().format(FORMATTER));
        }
        return response;
    }
}
