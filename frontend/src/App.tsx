import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import MainLayout from './layouts/MainLayout';
import ChatPage from './pages/ChatPage';
import KnowledgePage from './pages/KnowledgePage';
import AgentPage from './pages/AgentPage';
import SessionPage from './pages/SessionPage';
import DashboardPage from './pages/DashboardPage';
import PromptPage from './pages/PromptPage';
import HandoverRulePage from './pages/HandoverRulePage';
import ModelConfigPage from './pages/ModelConfigPage';
import ToolConfigPage from './pages/ToolConfigPage';
import SkillConfigPage from './pages/SkillConfigPage';
import StrategyConfigPage from './pages/StrategyConfigPage';
import LoginPage from './pages/LoginPage';
import UserListPage from './pages/UserListPage';
import ApplicationListPage from './pages/ApplicationListPage';
import TenantListPage from './pages/TenantListPage';
import PlatformOverviewPage from './pages/PlatformOverviewPage';
import SharedModelPage from './pages/SharedModelPage';
import SharedToolPage from './pages/SharedToolPage';
import PlatformConfigPage from './pages/PlatformConfigPage';
import TenantConfigPage from './pages/TenantConfigPage';
import AuditLogPage from './pages/AuditLogPage';
import IntegrationPage from './pages/IntegrationPage';
import UsagePage from './pages/UsagePage';
import AbTestPage from './pages/AbTestPage';
import RequireApp from './components/RequireApp';
import { Toaster } from './components/ui/toaster';
import { useAuthStore } from './stores/authStore';

// 路由守卫组件
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuthStore();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
};

function App() {
  return (
    <Router>
      <Routes>
        {/* 登录页面 - 无需认证 */}
        <Route path="/login" element={<LoginPage />} />

        {/* 受保护的路由 */}
        <Route path="/" element={
          <ProtectedRoute>
            <MainLayout />
          </ProtectedRoute>
        }>
          <Route index element={<Navigate to="/chat" replace />} />
          {/* 客服中心 */}
          <Route path="chat" element={<ChatPage />} />
          <Route path="agent" element={<AgentPage />} />
          <Route path="sessions" element={<SessionPage />} />
          {/* 应用配置（需先选择应用） */}
          <Route path="knowledge" element={<RequireApp><KnowledgePage /></RequireApp>} />
          <Route path="prompts" element={<RequireApp><PromptPage /></RequireApp>} />
          <Route path="strategies" element={<RequireApp><StrategyConfigPage /></RequireApp>} />
          <Route path="ai-models" element={<RequireApp><ModelConfigPage /></RequireApp>} />
          <Route path="ai-tools" element={<RequireApp><ToolConfigPage /></RequireApp>} />
          <Route path="ai-skills" element={<RequireApp><SkillConfigPage /></RequireApp>} />
          <Route path="handover-rules" element={<RequireApp><HandoverRulePage /></RequireApp>} />
          <Route path="integration" element={<RequireApp><IntegrationPage /></RequireApp>} />
          <Route path="ab-tests" element={<RequireApp><AbTestPage /></RequireApp>} />
          {/* 管理 */}
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="usage" element={<UsagePage />} />
          <Route path="users" element={<UserListPage />} />
          <Route path="applications" element={<ApplicationListPage />} />
          <Route path="tenants" element={<TenantListPage />} />
          <Route path="platform/overview" element={<PlatformOverviewPage />} />
          <Route path="platform/models" element={<SharedModelPage />} />
          <Route path="platform/tools" element={<SharedToolPage />} />
          <Route path="platform/config" element={<PlatformConfigPage />} />
          <Route path="tenant/config" element={<TenantConfigPage />} />
          <Route path="audit-logs" element={<AuditLogPage />} />
        </Route>
      </Routes>
      <Toaster />
    </Router>
  );
}

export default App;
