package com.example.ragcsdn.service.impl;

import com.example.ragcsdn.dto.request.LoginRequest;
import com.example.ragcsdn.dto.request.RegisterRequest;
import com.example.ragcsdn.dto.request.UpdateCsdnSessionRequest;
import com.example.ragcsdn.dto.response.UserResponse;
import com.example.ragcsdn.entity.User;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import com.example.ragcsdn.mapper.UserMapper;
import com.example.ragcsdn.service.UserService;
import com.example.ragcsdn.service.user.CsdnCookieNormalizer;
import com.example.ragcsdn.service.user.UserResponseAssembler;
import com.example.ragcsdn.util.CredentialCryptoService;
import com.example.ragcsdn.util.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private CredentialCryptoService credentialCryptoService;

    @Autowired
    private CsdnCookieNormalizer csdnCookieNormalizer;

    @Autowired
    private UserResponseAssembler userResponseAssembler;

    @Override
    public UserResponse register(RegisterRequest request) {
        // 检查用户名是否已存在
        User existingUser = userMapper.selectByUsername(request.getUsername());
        if (existingUser != null) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // 创建新用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(PasswordEncoder.encode(request.getPassword()));
        user.setCreateTime(LocalDateTime.now());

        // 插入数据库
        userMapper.insert(user);

        return userResponseAssembler.toResponse(user);
    }

    @Override
    public UserResponse login(LoginRequest request) {
        // 查询用户
        User user = userMapper.selectByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 验证密码
        if (!PasswordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        return userResponseAssembler.toResponse(user);
    }

    @Override
    public void logout(Long userId) {
        // Session 认证方式，登出由 Controller 处理 session.invalidate()
        // 这里不需要额外逻辑
    }

    @Override
    public UserResponse getCurrentUser(Long userId) {
        return userResponseAssembler.toResponse(requireUser(userId));
    }

    @Override
    public UserResponse saveCsdnSession(UpdateCsdnSessionRequest request, Long userId) {
        User user = requireUser(userId);
        String normalizedCookie = csdnCookieNormalizer.normalize(request.getCookie());
        String encryptedCookie = credentialCryptoService.encrypt(normalizedCookie);
        LocalDateTime now = LocalDateTime.now();

        userMapper.updateCsdnCookie(userId, encryptedCookie, now);
        user.setCsdnCookieEncrypted(encryptedCookie);
        user.setCsdnCookieUpdateTime(now);
        return userResponseAssembler.toResponse(user);
    }

    @Override
    public UserResponse clearCsdnSession(Long userId) {
        User user = requireUser(userId);
        userMapper.updateCsdnCookie(userId, null, null);
        user.setCsdnCookieEncrypted(null);
        user.setCsdnCookieUpdateTime(null);
        return userResponseAssembler.toResponse(user);
    }

    @Override
    public String getCsdnSessionCookie(Long userId) {
        User user = requireUser(userId);
        if (user.getCsdnCookieEncrypted() == null || user.getCsdnCookieEncrypted().isBlank()) {
            return null;
        }
        return credentialCryptoService.decrypt(user.getCsdnCookieEncrypted());
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

}
