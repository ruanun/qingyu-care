import React, { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Smartphone, Plus } from 'lucide-react';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { applicationService } from '@/api/application';
import { useAppStore } from '@/stores/appStore';

/**
 * 应用切换器
 * 位于侧栏顶部：拉取当前用户可见应用，选择后写入 appStore，
 * 请求随即携带 X-App-Id，实现按应用隔离。
 */
const AppSwitcher: React.FC = () => {
    const navigate = useNavigate();
    const { currentApp, apps, setApps, setCurrentApp } = useAppStore();

    useEffect(() => {
        let mounted = true;
        applicationService.list()
            .then((res) => {
                if (!mounted) return;
                const list = res.data ?? [];
                setApps(list);
                // 默认选中：保留仍存在的已选应用，否则选 id 最小（最早创建）者
                const stillExists = currentApp && list.some((a) => a.id === currentApp.id);
                if (!stillExists) {
                    if (list.length > 0) {
                        const first = [...list].sort((a, b) => a.id - b.id)[0];
                        setCurrentApp({ id: first.id, name: first.name });
                    } else {
                        setCurrentApp(null);
                    }
                }
            })
            .catch(() => {
                // 拉取失败保持现状，不阻断页面
            });
        return () => { mounted = false; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (apps.length === 0) {
        return (
            <button
                onClick={() => navigate('/applications')}
                className="flex w-full items-center gap-2 rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground hover:bg-muted/50"
            >
                <Plus className="h-4 w-4" />
                暂无应用，去创建
            </button>
        );
    }

    return (
        <Select
            value={currentApp ? String(currentApp.id) : undefined}
            onValueChange={(val) => {
                const app = apps.find((a) => String(a.id) === val);
                if (app) setCurrentApp({ id: app.id, name: app.name });
            }}
        >
            <SelectTrigger className="w-full">
                <div className="flex items-center gap-2 overflow-hidden">
                    <Smartphone className="h-4 w-4 shrink-0" />
                    <SelectValue placeholder="选择应用" />
                </div>
            </SelectTrigger>
            <SelectContent>
                {apps.map((app) => (
                    <SelectItem key={app.id} value={String(app.id)}>
                        {app.name}
                    </SelectItem>
                ))}
            </SelectContent>
        </Select>
    );
};

export default AppSwitcher;
