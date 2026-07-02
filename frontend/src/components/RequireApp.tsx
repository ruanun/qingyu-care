import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Smartphone } from 'lucide-react';
import { useAppStore } from '@/stores/appStore';

/**
 * 应用上下文守卫
 * 包裹"应用配置"类页面：未选择当前应用时展示空状态，引导先选/创建应用。
 */
const RequireApp: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const navigate = useNavigate();
    const { currentApp, apps } = useAppStore();

    if (!currentApp) {
        return (
            <div className="flex h-full flex-col items-center justify-center gap-4 text-center text-muted-foreground">
                <Smartphone className="h-12 w-12 opacity-40" />
                <div>
                    <p className="text-base font-medium">请先选择一个应用</p>
                    <p className="text-sm">
                        {apps.length === 0
                            ? '当前还没有应用，请先创建。'
                            : '在左上角切换器中选择要配置的应用。'}
                    </p>
                </div>
                {apps.length === 0 && (
                    <button
                        onClick={() => navigate('/applications')}
                        className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground"
                    >
                        去创建应用
                    </button>
                )}
            </div>
        );
    }

    return <>{children}</>;
};

export default RequireApp;
