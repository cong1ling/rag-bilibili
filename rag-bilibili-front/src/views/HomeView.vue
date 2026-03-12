<template>
  <div class="home-page">
    <!-- Hero Section -->
    <section class="hero-section">
      <div class="hero-background">
        <div class="gradient-orb orb-1"></div>
        <div class="gradient-orb orb-2"></div>
        <div class="gradient-orb orb-3"></div>
      </div>

      <div class="hero-content">
        <div class="hero-badge">
          <span class="badge-icon">🎬</span>
          <span>AI驱动的视频知识管理</span>
        </div>

        <h1 class="hero-title">
          把B站视频<br />
          变成<span class="gradient-text">随时可问</span>的知识库
        </h1>

        <p class="hero-description">
          导入视频，AI自动提取字幕并向量化存储。随时提问，智能检索相关内容并生成精准回答。
          让每一条看过的视频，都成为你的个人知识资产。
        </p>

        <div class="hero-actions">
          <RouterLink to="/login" class="btn btn-primary">
            <span>开始使用</span>
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <path d="M7.5 15L12.5 10L7.5 5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </RouterLink>
          <a href="#features" class="btn btn-secondary">了解更多</a>
        </div>

        <div class="hero-stats">
          <div class="stat-item">
            <div class="stat-number">3步</div>
            <div class="stat-label">快速上手</div>
          </div>
          <div class="stat-divider"></div>
          <div class="stat-item">
            <div class="stat-number">AI驱动</div>
            <div class="stat-label">智能问答</div>
          </div>
          <div class="stat-divider"></div>
          <div class="stat-item">
            <div class="stat-number">无限</div>
            <div class="stat-label">视频容量</div>
          </div>
        </div>
      </div>
    </section>

    <!-- Features Section -->
    <section id="features" class="features-section">
      <div class="section-header">
        <span class="section-badge">核心功能</span>
        <h2 class="section-title">为什么选择 RAG Bilibili</h2>
        <p class="section-description">
          结合检索增强生成(RAG)技术，让视频内容真正为你所用
        </p>
      </div>

      <div class="features-grid">
        <div class="feature-card">
          <div class="feature-icon">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <path d="M16 4L4 10L16 16L28 10L16 4Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M4 22L16 28L28 22" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M4 16L16 22L28 16" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <h3 class="feature-title">智能导入</h3>
          <p class="feature-description">
            输入B站视频链接，自动获取字幕、分段处理并向量化存储。支持单个视频和批量导入。
          </p>
        </div>

        <div class="feature-card">
          <div class="feature-icon">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <circle cx="16" cy="16" r="12" stroke="currentColor" stroke-width="2"/>
              <path d="M16 8V16L20 20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </div>
          <h3 class="feature-title">实时问答</h3>
          <p class="feature-description">
            基于视频内容提问，AI检索相关片段并流式生成回答。支持追问和多轮对话。
          </p>
        </div>

        <div class="feature-card">
          <div class="feature-icon">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <rect x="4" y="4" width="24" height="24" rx="2" stroke="currentColor" stroke-width="2"/>
              <path d="M4 12H28" stroke="currentColor" stroke-width="2"/>
              <path d="M12 4V12" stroke="currentColor" stroke-width="2"/>
            </svg>
          </div>
          <h3 class="feature-title">会话管理</h3>
          <p class="feature-description">
            创建单视频或全库会话，历史对话自动保存。随时回顾，继续深入探讨。
          </p>
        </div>

        <div class="feature-card">
          <div class="feature-icon">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <path d="M28 16C28 22.6274 22.6274 28 16 28C9.37258 28 4 22.6274 4 16C4 9.37258 9.37258 4 16 4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <path d="M16 4C19.3137 4 22 6.68629 22 10C22 13.3137 19.3137 16 16 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </div>
          <h3 class="feature-title">向量检索</h3>
          <p class="feature-description">
            使用DashVector向量数据库，语义相似度搜索，精准定位相关内容片段。
          </p>
        </div>

        <div class="feature-card">
          <div class="feature-icon">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <path d="M16 4V28M4 16H28" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              <circle cx="16" cy="16" r="8" stroke="currentColor" stroke-width="2"/>
            </svg>
          </div>
          <h3 class="feature-title">跨视频查询</h3>
          <p class="feature-description">
            在全部导入的视频中搜索，发现共性、对比差异，构建个人知识网络。
          </p>
        </div>

        <div class="feature-card">
          <div class="feature-icon">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <path d="M8 16L14 22L24 10" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              <circle cx="16" cy="16" r="12" stroke="currentColor" stroke-width="2"/>
            </svg>
          </div>
          <h3 class="feature-title">Markdown渲染</h3>
          <p class="feature-description">
            AI回答支持Markdown格式，代码高亮、表格、列表，内容结构清晰易读。
          </p>
        </div>
      </div>
    </section>

    <!-- How It Works Section -->
    <section class="how-it-works-section">
      <div class="section-header">
        <span class="section-badge">使用流程</span>
        <h2 class="section-title">三步开始使用</h2>
      </div>

      <div class="steps-container">
        <div class="step-item">
          <div class="step-number">01</div>
          <div class="step-content">
            <h3 class="step-title">导入视频</h3>
            <p class="step-description">
              粘贴B站视频链接，系统自动获取字幕并处理。支持单个或批量导入。
            </p>
          </div>
          <div class="step-arrow">→</div>
        </div>

        <div class="step-item">
          <div class="step-number">02</div>
          <div class="step-content">
            <h3 class="step-title">创建会话</h3>
            <p class="step-description">
              选择单个视频或全部视频范围，创建问答会话，开始提问。
            </p>
          </div>
          <div class="step-arrow">→</div>
        </div>

        <div class="step-item">
          <div class="step-number">03</div>
          <div class="step-content">
            <h3 class="step-title">智能问答</h3>
            <p class="step-description">
              提出问题，AI检索相关内容并生成回答。支持追问和多轮对话。
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- CTA Section -->
    <section class="cta-section">
      <div class="cta-content">
        <h2 class="cta-title">准备好开始了吗？</h2>
        <p class="cta-description">
          立即注册，把你的B站收藏变成可问答的知识库
        </p>
        <div class="cta-actions">
          <RouterLink to="/register" class="btn btn-primary btn-large">
            免费注册
          </RouterLink>
          <RouterLink to="/login" class="btn btn-secondary btn-large">
            已有账号，登录
          </RouterLink>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="footer">
      <div class="footer-content">
        <div class="footer-brand">
          <div class="footer-logo">
            <span class="logo-icon">RB</span>
            <span class="logo-text">RAG Bilibili</span>
          </div>
          <p class="footer-tagline">把视频变成知识库</p>
        </div>

        <div class="footer-links">
          <div class="footer-column">
            <h4 class="footer-column-title">产品</h4>
            <RouterLink to="/login" class="footer-link">登录</RouterLink>
            <RouterLink to="/register" class="footer-link">注册</RouterLink>
          </div>

          <div class="footer-column">
            <h4 class="footer-column-title">技术栈</h4>
            <span class="footer-text">Spring AI</span>
            <span class="footer-text">Vue 3</span>
            <span class="footer-text">DashVector</span>
          </div>
        </div>
      </div>

      <div class="footer-bottom">
        <p class="footer-copyright">© 2024 RAG Bilibili. 基于检索增强生成技术的视频知识管理平台。</p>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { RouterLink } from "vue-router";
</script>

<style scoped>
.home-page {
  min-height: 100vh;
  background: linear-gradient(180deg, #0f1419 0%, #1a1f2e 100%);
  color: #ffffff;
}

/* Hero Section */
.hero-section {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  overflow: hidden;
}

.hero-background {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.gradient-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.3;
  animation: float 20s ease-in-out infinite;
}

.orb-1 {
  width: 600px;
  height: 600px;
  background: linear-gradient(135deg, #ff6b35 0%, #f7931e 100%);
  top: -200px;
  right: -200px;
  animation-delay: 0s;
}

.orb-2 {
  width: 500px;
  height: 500px;
  background: linear-gradient(135deg, #4a90e2 0%, #357abd 100%);
  bottom: -150px;
  left: -150px;
  animation-delay: 5s;
}

.orb-3 {
  width: 400px;
  height: 400px;
  background: linear-gradient(135deg, #9b59b6 0%, #8e44ad 100%);
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  animation-delay: 10s;
}

@keyframes float {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  33% {
    transform: translate(30px, -30px) scale(1.1);
  }
  66% {
    transform: translate(-20px, 20px) scale(0.9);
  }
}

.hero-content {
  position: relative;
  z-index: 1;
  max-width: 900px;
  text-align: center;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 50px;
  font-size: 0.875rem;
  margin-bottom: 2rem;
}

.badge-icon {
  font-size: 1.25rem;
}

.hero-title {
  font-size: 4rem;
  font-weight: 700;
  line-height: 1.2;
  margin-bottom: 1.5rem;
  letter-spacing: -0.02em;
}

.gradient-text {
  background: linear-gradient(135deg, #ff6b35 0%, #f7931e 50%, #4a90e2 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.hero-description {
  font-size: 1.25rem;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.8);
  margin-bottom: 2.5rem;
  max-width: 700px;
  margin-left: auto;
  margin-right: auto;
}

.hero-actions {
  display: flex;
  gap: 1rem;
  justify-content: center;
  margin-bottom: 4rem;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem 2rem;
  border-radius: 8px;
  font-size: 1rem;
  font-weight: 600;
  text-decoration: none;
  transition: all 0.3s ease;
  cursor: pointer;
  border: none;
}

.btn-primary {
  background: linear-gradient(135deg, #ff6b35 0%, #f7931e 100%);
  color: #ffffff;
}

.btn-primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 30px rgba(255, 107, 53, 0.4);
}

.btn-secondary {
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: #ffffff;
}

.btn-secondary:hover {
  background: rgba(255, 255, 255, 0.15);
  border-color: rgba(255, 255, 255, 0.3);
}

.btn-large {
  padding: 1.25rem 2.5rem;
  font-size: 1.125rem;
}

.hero-stats {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2rem;
}

.stat-item {
  text-align: center;
}

.stat-number {
  font-size: 2rem;
  font-weight: 700;
  background: linear-gradient(135deg, #ff6b35 0%, #f7931e 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.stat-label {
  font-size: 0.875rem;
  color: rgba(255, 255, 255, 0.6);
  margin-top: 0.25rem;
}

.stat-divider {
  width: 1px;
  height: 40px;
  background: rgba(255, 255, 255, 0.2);
}

/* Features Section */
.features-section {
  padding: 6rem 2rem;
  max-width: 1200px;
  margin: 0 auto;
}

.section-header {
  text-align: center;
  margin-bottom: 4rem;
}

.section-badge {
  display: inline-block;
  padding: 0.5rem 1rem;
  background: rgba(255, 107, 53, 0.1);
  border: 1px solid rgba(255, 107, 53, 0.3);
  border-radius: 50px;
  font-size: 0.875rem;
  color: #ff6b35;
  margin-bottom: 1rem;
}

.section-title {
  font-size: 3rem;
  font-weight: 700;
  margin-bottom: 1rem;
}

.section-description {
  font-size: 1.125rem;
  color: rgba(255, 255, 255, 0.7);
  max-width: 600px;
  margin: 0 auto;
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 2rem;
}

.feature-card {
  padding: 2rem;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  transition: all 0.3s ease;
}

.feature-card:hover {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 107, 53, 0.5);
  transform: translateY(-4px);
}

.feature-icon {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(255, 107, 53, 0.2) 0%, rgba(74, 144, 226, 0.2) 100%);
  border-radius: 12px;
  margin-bottom: 1.5rem;
  color: #ff6b35;
}

.feature-title {
  font-size: 1.5rem;
  font-weight: 600;
  margin-bottom: 0.75rem;
}

.feature-description {
  font-size: 1rem;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.7);
}

/* How It Works Section */
.how-it-works-section {
  padding: 6rem 2rem;
  background: rgba(255, 255, 255, 0.02);
}

.steps-container {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  gap: 2rem;
  align-items: center;
}

.step-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  position: relative;
}

.step-number {
  width: 80px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #ff6b35 0%, #f7931e 100%);
  border-radius: 50%;
  font-size: 2rem;
  font-weight: 700;
  margin-bottom: 1.5rem;
}

.step-content {
  flex: 1;
}

.step-title {
  font-size: 1.5rem;
  font-weight: 600;
  margin-bottom: 0.75rem;
}

.step-description {
  font-size: 1rem;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.7);
}

.step-arrow {
  position: absolute;
  right: -2rem;
  top: 40px;
  font-size: 2rem;
  color: rgba(255, 107, 53, 0.5);
}

.step-item:last-child .step-arrow {
  display: none;
}

/* CTA Section */
.cta-section {
  padding: 6rem 2rem;
  text-align: center;
}

.cta-content {
  max-width: 800px;
  margin: 0 auto;
  padding: 4rem;
  background: linear-gradient(135deg, rgba(255, 107, 53, 0.1) 0%, rgba(74, 144, 226, 0.1) 100%);
  border: 1px solid rgba(255, 107, 53, 0.3);
  border-radius: 20px;
}

.cta-title {
  font-size: 3rem;
  font-weight: 700;
  margin-bottom: 1rem;
}

.cta-description {
  font-size: 1.25rem;
  color: rgba(255, 255, 255, 0.8);
  margin-bottom: 2.5rem;
}

.cta-actions {
  display: flex;
  gap: 1rem;
  justify-content: center;
}

/* Footer */
.footer {
  padding: 4rem 2rem 2rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}

.footer-content {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  justify-content: space-between;
  margin-bottom: 3rem;
}

.footer-brand {
  max-width: 300px;
}

.footer-logo {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.logo-icon {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #ff6b35 0%, #f7931e 100%);
  border-radius: 8px;
  font-weight: 700;
  font-size: 1.25rem;
}

.logo-text {
  font-size: 1.25rem;
  font-weight: 700;
}

.footer-tagline {
  color: rgba(255, 255, 255, 0.6);
  font-size: 0.875rem;
}

.footer-links {
  display: flex;
  gap: 4rem;
}

.footer-column {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.footer-column-title {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.footer-link {
  color: rgba(255, 255, 255, 0.7);
  text-decoration: none;
  font-size: 0.875rem;
  transition: color 0.3s ease;
}

.footer-link:hover {
  color: #ff6b35;
}

.footer-text {
  color: rgba(255, 255, 255, 0.6);
  font-size: 0.875rem;
}

.footer-bottom {
  max-width: 1200px;
  margin: 0 auto;
  padding-top: 2rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  text-align: center;
}

.footer-copyright {
  color: rgba(255, 255, 255, 0.5);
  font-size: 0.875rem;
}

/* Responsive */
@media (max-width: 768px) {
  .hero-title {
    font-size: 2.5rem;
  }

  .hero-description {
    font-size: 1rem;
  }

  .hero-actions {
    flex-direction: column;
  }

  .hero-stats {
    flex-direction: column;
    gap: 1rem;
  }

  .stat-divider {
    width: 40px;
    height: 1px;
  }

  .section-title {
    font-size: 2rem;
  }

  .features-grid {
    grid-template-columns: 1fr;
  }

  .steps-container {
    flex-direction: column;
  }

  .step-arrow {
    display: none;
  }

  .cta-title {
    font-size: 2rem;
  }

  .cta-actions {
    flex-direction: column;
  }

  .footer-content {
    flex-direction: column;
    gap: 2rem;
  }
}
</style>
