package com.example.ragcsdn.service.impl;

import com.example.ragcsdn.dto.request.CreateSessionRequest;
import com.example.ragcsdn.dto.response.SessionResponse;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.SessionType;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import com.example.ragcsdn.mapper.MessageMapper;
import com.example.ragcsdn.mapper.SessionMapper;
import com.example.ragcsdn.mapper.ArticleMapper;
import com.example.ragcsdn.service.SessionService;
import com.example.ragcsdn.service.session.SessionCreationPolicy;
import com.example.ragcsdn.service.session.SessionResponseAssembler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SessionServiceImpl implements SessionService {
    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private SessionCreationPolicy sessionCreationPolicy;

    @Autowired
    private SessionResponseAssembler sessionResponseAssembler;

    @Override
    public SessionResponse createSession(CreateSessionRequest request, Long userId) {
        // 验证会话类型
        String normalizedSessionType = sessionCreationPolicy.validateAndNormalize(request, userId);

        // 创建会话
        Session session = new Session();
        session.setUserId(userId);
        session.setSessionType(normalizedSessionType);
        session.setArticleId(request.getArticleId());
        session.setConversationSummary(null);
        session.setSummaryUpdateTime(null);
        session.setCreateTime(LocalDateTime.now());

        sessionMapper.insert(session);

        return sessionResponseAssembler.toResponse(session);
    }

    @Override
    public List<SessionResponse> listSessions(Long userId) {
        List<Session> sessions = sessionMapper.selectByUserId(userId);
        return sessions.stream()
                .map(sessionResponseAssembler::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public SessionResponse getSession(Long sessionId, Long userId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return sessionResponseAssembler.toResponse(session);
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        // 验证会话是否存在且属于当前用户
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        // 删除会话关联的消息
        messageMapper.deleteBySessionId(sessionId);

        // 删除会话
        sessionMapper.deleteById(sessionId);
    }

}
