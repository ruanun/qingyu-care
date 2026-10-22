import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import { userService } from '@/api/user';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

/**
 * 本人修改密码。必须填当前密码：后端 `POST /v1/users/me/password` 会校验它，
 * 持有一个被盗的登录态不等于知道密码（2026-09-30 安全审查 M3）。
 * 猜错当前密码与登录共用失败计数，连错 5 次会锁 15 分钟。
 *
 * @author ruan
 */
export default function ChangePasswordDialog({ open, onOpenChange }: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const { toast } = useToast();
    const [oldPassword, setOldPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [submitting, setSubmitting] = useState(false);

    // 关闭即清空：密码不该在关掉弹窗后还留在 state 里，下次打开时被看到
    const handleOpenChange = (next: boolean) => {
        if (!next) {
            setOldPassword('');
            setNewPassword('');
            setConfirmPassword('');
        }
        onOpenChange(next);
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (newPassword !== confirmPassword) {
            toast({ variant: 'destructive', title: '两次输入的新密码不一致' });
            return;
        }
        setSubmitting(true);
        try {
            const res = await userService.changeMyPassword({ oldPassword, newPassword });
            throwIfFailed(res);
            toast({ title: '密码已修改', description: '其他设备上的登录已失效，需要重新登录' });
            handleOpenChange(false);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '修改失败', description: getErrorMessage(error) });
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="sm:max-w-[400px]">
                <DialogHeader>
                    <DialogTitle>修改密码</DialogTitle>
                    <DialogDescription>修改后，其他设备上的登录会失效。</DialogDescription>
                </DialogHeader>
                <form onSubmit={handleSubmit} className="space-y-4">
                    <div className="space-y-2">
                        <label htmlFor="old-password" className="text-sm font-medium">当前密码</label>
                        <Input id="old-password" type="password" required autoComplete="current-password"
                               value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} />
                    </div>
                    <div className="space-y-2">
                        <label htmlFor="new-password" className="text-sm font-medium">新密码</label>
                        <Input id="new-password" type="password" required autoComplete="new-password"
                               value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
                    </div>
                    <div className="space-y-2">
                        <label htmlFor="confirm-password" className="text-sm font-medium">确认新密码</label>
                        <Input id="confirm-password" type="password" required autoComplete="new-password"
                               value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} />
                    </div>
                    <DialogFooter className="pt-2">
                        <Button type="button" variant="ghost" onClick={() => handleOpenChange(false)}>取消</Button>
                        <Button type="submit" disabled={submitting}>
                            {submitting && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                            确认修改
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
