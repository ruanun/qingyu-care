import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { LogIn, User, Lock, Building2, Loader2, Sparkles, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import Logo from '@/components/Logo';
import TurnstileWidget from '@/components/TurnstileWidget';
import { authService } from '@/api/auth';
import type { CaptchaMode, ImageCaptcha } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage } from '@/lib/apiResult';
import { isRememberMe, setRememberMe } from '@/lib/authStorage';

const LoginPage: React.FC = () => {
    const navigate = useNavigate();
    const { setAuth } = useAuthStore();
    const { toast } = useToast();

    const [isLoading, setIsLoading] = useState(false);
    // 是否开放自助注册由后端决定（生产默认关闭）。未拿到结果前按关闭处理：接口出错时也不能把注册入口露出来
    const [selfRegister, setSelfRegister] = useState(false);
    const [captchaMode, setCaptchaMode] = useState<CaptchaMode>('NONE');
    const [turnstileSiteKey, setTurnstileSiteKey] = useState('');
    useEffect(() => {
        authService.getPublicConfig()
            .then((res) => {
                const enabled = res.code === 200 && res.data?.selfRegisterEnabled === true;
                setSelfRegister(enabled);
                if (enabled) {
                    setCaptchaMode(res.data.captchaMode ?? 'NONE');
                    setTurnstileSiteKey(res.data.turnstileSiteKey ?? '');
                }
            })
            .catch(() => setSelfRegister(false));
    }, []);

    const [tab, setTab] = useState('login');
    // 图形验证码：切到注册页才取第一张（只登录的人不必白占一个），提交失败后换一张——答案取出即作废
    const [captcha, setCaptcha] = useState<ImageCaptcha | null>(null);
    const [captchaCode, setCaptchaCode] = useState('');
    const loadCaptcha = async () => {
        setCaptchaCode('');
        try {
            const res = await authService.getCaptcha();
            if (res.code !== 200) throw new Error(res.message);
            setCaptcha(res.data);
        } catch (error: unknown) {
            setCaptcha(null);
            toast({ variant: 'destructive', title: '验证码加载失败', description: getErrorMessage(error) });
        }
    };
    // Turnstile 令牌同样一次性：提交失败后递增 resetSignal 让组件重新出题
    const [turnstileToken, setTurnstileToken] = useState<string | null>(null);
    const [turnstileReset, setTurnstileReset] = useState(0);

    const handleTabChange = (value: string) => {
        setTab(value);
        if (value === 'register' && captchaMode === 'IMAGE' && !captcha) {
            void loadCaptcha();
        }
    };
    // 回显上次的选择；不勾则登录态只存 sessionStorage，关掉标签页即需重新登录
    const [remember, setRemember] = useState(isRememberMe);

    // 登录表单
    const [loginForm, setLoginForm] = useState({
        username: '',
        password: '',
    });

    // 注册表单
    const [registerForm, setRegisterForm] = useState({
        username: '',
        password: '',
        nickname: '',
        tenantName: '',
    });

    const handleLogin = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!loginForm.username || !loginForm.password) {
            toast({ variant: 'destructive', title: '请填写用户名和密码' });
            return;
        }

        setIsLoading(true);
        try {
            const res = await authService.login(loginForm);
            if (res.code === 200 && res.data) {
                // 必须在 setAuth 之前：persist 在 setAuth 时写入，写到哪个 storage 由这个选择决定
                setRememberMe(remember);
                setAuth(res.data);
                toast({ title: '登录成功', description: `欢迎回来，${res.data.nickname || res.data.username}` });
                navigate('/');
            } else {
                toast({ variant: 'destructive', title: '登录失败', description: res.message });
            }
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '登录失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    const handleRegister = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!registerForm.username || !registerForm.password) {
            toast({ variant: 'destructive', title: '请填写用户名和密码' });
            return;
        }
        if (captchaMode === 'IMAGE' && (!captcha || !captchaCode.trim())) {
            toast({ variant: 'destructive', title: '请输入验证码' });
            return;
        }
        if (captchaMode === 'TURNSTILE' && !turnstileToken) {
            toast({ variant: 'destructive', title: '请先完成人机验证' });
            return;
        }

        setIsLoading(true);
        let succeeded = false;
        try {
            const res = await authService.register({
                ...registerForm,
                ...(captchaMode === 'IMAGE' ? { captchaId: captcha?.captchaId, captchaCode: captchaCode.trim() } : {}),
                ...(captchaMode === 'TURNSTILE' ? { turnstileToken: turnstileToken ?? undefined } : {}),
            });
            if (res.code === 200 && res.data) {
                succeeded = true;
                setAuth(res.data);
                toast({ title: '注册成功', description: '欢迎使用轻语 AI' });
                navigate('/');
            } else {
                toast({ variant: 'destructive', title: '注册失败', description: res.message });
            }
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '注册失败', description: getErrorMessage(error) });
        } finally {
            // 不论失败在哪一步，这次的验证码/令牌都可能已被服务端消耗，换新的才能再提交
            if (!succeeded) {
                if (captchaMode === 'IMAGE') void loadCaptcha();
                if (captchaMode === 'TURNSTILE') setTurnstileReset((n) => n + 1);
            }
            setIsLoading(false);
        }
    };

    return (
        <div className="min-h-screen w-full flex items-center justify-center bg-[#f8fafc] overflow-hidden relative">
            {/* 背景动态装饰 */}
            <div className="absolute top-[-10%] left-[-10%] w-[40%] h-[40%] bg-primary/5 rounded-full blur-[120px]" />
            <div className="absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] bg-purple-500/5 rounded-full blur-[120px]" />

            <div className="w-full max-w-[1100px] grid lg:grid-cols-2 gap-0 bg-white rounded-3xl shadow-[0_32px_64px_-16px_rgba(0,0,0,0.1)] overflow-hidden relative z-10 border border-slate-100">
                {/* 左侧：品牌展示区 */}
                <div className="hidden lg:flex flex-col justify-between p-12 bg-linear-to-br from-slate-900 to-slate-800 text-white relative overflow-hidden">
                    <div className="absolute inset-0 bg-[url('https://grainy-gradients.vercel.app/noise.svg')] opacity-20 brightness-100 contrast-150 pointer-events-none" />

                    <div className="relative z-10">
                        <div className="flex items-center gap-2 mb-12">
                            {/* 深色卡片上用单色版：挖空的笔画透出卡片底色 */}
                            <Logo variant="mono" className="h-10 w-10 text-white" />
                            <span className="text-2xl font-bold tracking-tight">QingYu AI</span>
                        </div>

                        <div className="space-y-6">
                            <h2 className="text-4xl font-extrabold leading-tight">
                                下一代 <span className="text-blue-400">智能客服</span> <br />
                                SaaS 管理平台
                            </h2>
                            <p className="text-slate-400 text-lg max-w-[320px]">
                                极简设计，极致效率。为数智化客户服务而生。
                            </p>
                        </div>
                    </div>

                    <div className="relative z-10 space-y-4">
                        <div className="flex items-center gap-3 p-4 bg-white/5 backdrop-blur-md rounded-2xl border border-white/10">
                            <div className="w-8 h-8 rounded-full bg-green-500/20 flex items-center justify-center">
                                <Sparkles className="w-4 h-4 text-green-400" />
                            </div>
                            <p className="text-sm text-slate-300">多租户隔离架构，数据安全有保障</p>
                        </div>
                    </div>
                </div>

                {/* 右侧：登录/注册表单区 */}
                <div className="p-8 lg:p-16 flex flex-col justify-center bg-white">
                    <div className="mb-8 lg:hidden flex justify-center">
                        {/* 移动端这处是白底，必须用渐变版——单色白贴白底会整个消失 */}
                        <Logo className="h-12 w-12" />
                    </div>

                    <div className="text-center lg:text-left mb-8">
                        <h1 className="text-3xl font-bold text-slate-900 mb-2">欢迎回来</h1>
                        <p className="text-slate-500 font-medium">请登录您的账号以继续</p>
                    </div>

                    <Tabs value={tab} onValueChange={handleTabChange} className="w-full">
                        {selfRegister && (
                        <TabsList className="grid w-full grid-cols-2 h-12 p-1 bg-slate-100 rounded-xl mb-8">
                            <TabsTrigger value="login" className="rounded-lg data-[state=active]:bg-white data-[state=active]:shadow-xs text-slate-600">
                                账号登录
                            </TabsTrigger>
                            <TabsTrigger value="register" className="rounded-lg data-[state=active]:bg-white data-[state=active]:shadow-xs text-slate-600">
                                免费注册
                            </TabsTrigger>
                        </TabsList>
                        )}

                        <TabsContent value="login">
                            <form onSubmit={handleLogin} className="space-y-5">
                                <div className="space-y-2">
                                    <div className="relative group">
                                        <div className="absolute left-4 top-1/2 -translate-y-1/2 text-slate-400 group-focus-within:text-primary transition-colors">
                                            <User className="h-5 w-5" />
                                        </div>
                                        <Input
                                            placeholder="用户名"
                                            className="h-14 pl-12 bg-slate-50 border-slate-200 rounded-xl focus:bg-white focus:ring-4 focus:ring-primary/10 transition-all text-base"
                                            value={loginForm.username}
                                            onChange={(e) => setLoginForm({ ...loginForm, username: e.target.value })}
                                        />
                                    </div>
                                    <div className="relative group">
                                        <div className="absolute left-4 top-1/2 -translate-y-1/2 text-slate-400 group-focus-within:text-primary transition-colors">
                                            <Lock className="h-5 w-5" />
                                        </div>
                                        <Input
                                            type="password"
                                            placeholder="密码"
                                            className="h-14 pl-12 bg-slate-50 border-slate-200 rounded-xl focus:bg-white focus:ring-4 focus:ring-primary/10 transition-all text-base"
                                            value={loginForm.password}
                                            onChange={(e) => setLoginForm({ ...loginForm, password: e.target.value })}
                                        />
                                    </div>
                                </div>

                                <div className="flex items-center justify-between text-sm py-1">
                                    <label className="flex items-center gap-2 cursor-pointer text-slate-500">
                                        <Checkbox checked={remember} onCheckedChange={(v) => setRemember(v === true)} />
                                        <span>记住我</span>
                                    </label>
                                    {/* 没有邮件/短信通道，做不了自助找回；租户管理员可在「成员管理」编辑成员时重置密码 */}
                                    <button
                                        type="button"
                                        className="text-primary font-semibold hover:underline transition-all"
                                        onClick={() => toast({ title: '忘记密码', description: '请联系所在企业的管理员，在「成员管理」中为你重置密码。' })}
                                    >
                                        忘记密码？
                                    </button>
                                </div>

                                <Button type="submit" className="w-full h-14 rounded-xl text-lg font-bold shadow-xl shadow-primary/20 hover:shadow-primary/30 active:scale-[0.98] transition-all" disabled={isLoading}>
                                    {isLoading ? (
                                        <Loader2 className="w-5 h-5 mr-3 animate-spin" />
                                    ) : (
                                        <LogIn className="w-5 h-5 mr-3" />
                                    )}
                                    立即登录
                                </Button>

                                <div className="mt-8 pt-8 border-t border-slate-100 flex justify-center gap-4 text-xs text-slate-400 items-center">
                                    <span>测试账号: admin / admin123</span>
                                </div>
                            </form>
                        </TabsContent>

                        {selfRegister && (
                        <TabsContent value="register">
                            <form onSubmit={handleRegister} className="space-y-4">
                                <div className="grid grid-cols-1 gap-4">
                                    <div className="relative group">
                                        <User className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400 group-focus-within:text-primary" />
                                        <Input
                                            placeholder="用户名"
                                            className="h-12 pl-12 bg-slate-50 border-slate-200 rounded-xl"
                                            value={registerForm.username}
                                            onChange={(e) => setRegisterForm({ ...registerForm, username: e.target.value })}
                                        />
                                    </div>
                                    <div className="relative group">
                                        <Lock className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400 group-focus-within:text-primary" />
                                        <Input
                                            type="password"
                                            placeholder="密码"
                                            className="h-12 pl-12 bg-slate-50 border-slate-200 rounded-xl"
                                            value={registerForm.password}
                                            onChange={(e) => setRegisterForm({ ...registerForm, password: e.target.value })}
                                        />
                                    </div>
                                    <div className="relative group">
                                        <User className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400 group-focus-within:text-primary" />
                                        <Input
                                            placeholder="昵称 (可选)"
                                            className="h-12 pl-12 bg-slate-50 border-slate-200 rounded-xl"
                                            value={registerForm.nickname}
                                            onChange={(e) => setRegisterForm({ ...registerForm, nickname: e.target.value })}
                                        />
                                    </div>
                                    <div className="relative group">
                                        <Building2 className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400 group-focus-within:text-primary" />
                                        <Input
                                            placeholder="企业名称 (作为初始租户)"
                                            className="h-12 pl-12 bg-slate-50 border-slate-200 rounded-xl"
                                            value={registerForm.tenantName}
                                            onChange={(e) => setRegisterForm({ ...registerForm, tenantName: e.target.value })}
                                        />
                                    </div>
                                </div>
                                {captchaMode === 'IMAGE' && (
                                    <div className="flex gap-3">
                                        <div className="relative group flex-1">
                                            <ShieldCheck className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400 group-focus-within:text-primary" />
                                            <Input
                                                placeholder="验证码"
                                                autoComplete="off"
                                                maxLength={8}
                                                className="h-12 pl-12 bg-slate-50 border-slate-200 rounded-xl"
                                                value={captchaCode}
                                                onChange={(e) => setCaptchaCode(e.target.value)}
                                            />
                                        </div>
                                        <button
                                            type="button"
                                            title="看不清？换一张"
                                            className="h-12 w-[120px] shrink-0 rounded-xl border border-slate-200 bg-slate-50 overflow-hidden text-xs text-slate-400"
                                            onClick={() => void loadCaptcha()}
                                        >
                                            {captcha ? <img src={captcha.image} alt="验证码" className="h-full w-full object-cover" /> : '点击获取'}
                                        </button>
                                    </div>
                                )}
                                {captchaMode === 'TURNSTILE' && turnstileSiteKey && (
                                    <TurnstileWidget siteKey={turnstileSiteKey} onToken={setTurnstileToken} resetSignal={turnstileReset} />
                                )}
                                <Button type="submit" className="w-full h-12 rounded-xl text-lg font-bold mt-4" disabled={isLoading}>
                                    {isLoading ? (
                                        <Loader2 className="w-5 h-5 mr-3 animate-spin" />
                                    ) : (
                                        <LogIn className="w-5 h-5 mr-3" />
                                    )}
                                    创建账号
                                </Button>
                            </form>
                        </TabsContent>
                        )}
                    </Tabs>
                </div>
            </div>
        </div>
    );
};

export default LoginPage;
