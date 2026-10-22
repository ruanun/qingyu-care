import React, { useEffect, useRef, useState } from 'react';

/**
 * Cloudflare Turnstile 人机验证组件（显式渲染模式）。
 *
 * 脚本按需加载、全页只加载一次；令牌经 onToken 回调给父组件，过期或出错时回调 null。
 * 令牌只能用一次：父组件在提交失败后递增 resetSignal，让组件重新出题。
 */

interface TurnstileApi {
    render: (el: HTMLElement, opts: Record<string, unknown>) => string;
    reset: (widgetId: string) => void;
    remove: (widgetId: string) => void;
}

declare global {
    interface Window {
        turnstile?: TurnstileApi;
    }
}

const SCRIPT_SRC = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
let scriptPromise: Promise<void> | null = null;

function loadScript(): Promise<void> {
    if (window.turnstile) return Promise.resolve();
    if (!scriptPromise) {
        scriptPromise = new Promise((resolve, reject) => {
            const el = document.createElement('script');
            el.src = SCRIPT_SRC;
            el.async = true;
            el.onload = () => resolve();
            el.onerror = () => {
                // 失败后允许下次重试，而不是把失败的 promise 永久缓存下来
                scriptPromise = null;
                el.remove();
                reject(new Error('Turnstile 脚本加载失败'));
            };
            document.head.appendChild(el);
        });
    }
    return scriptPromise;
}

interface TurnstileWidgetProps {
    siteKey: string;
    onToken: (token: string | null) => void;
    resetSignal?: number;
}

const TurnstileWidget: React.FC<TurnstileWidgetProps> = ({ siteKey, onToken, resetSignal = 0 }) => {
    const containerRef = useRef<HTMLDivElement>(null);
    const widgetIdRef = useRef<string | null>(null);
    // 回调走 ref：Turnstile 在 render 时记住的是那一刻的函数，父组件重渲染后不会更新
    const onTokenRef = useRef(onToken);
    useEffect(() => {
        onTokenRef.current = onToken;
    });
    const [loadError, setLoadError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        loadScript()
            .then(() => {
                if (cancelled || !containerRef.current || !window.turnstile) return;
                widgetIdRef.current = window.turnstile.render(containerRef.current, {
                    sitekey: siteKey,
                    callback: (token: string) => onTokenRef.current(token),
                    'expired-callback': () => onTokenRef.current(null),
                    'error-callback': () => onTokenRef.current(null),
                });
            })
            .catch(() => {
                if (!cancelled) setLoadError(true);
            });
        return () => {
            cancelled = true;
            if (widgetIdRef.current && window.turnstile) {
                window.turnstile.remove(widgetIdRef.current);
            }
            widgetIdRef.current = null;
        };
    }, [siteKey]);

    useEffect(() => {
        if (resetSignal > 0 && widgetIdRef.current && window.turnstile) {
            window.turnstile.reset(widgetIdRef.current);
            onTokenRef.current(null);
        }
    }, [resetSignal]);

    if (loadError) {
        return <p className="text-sm text-destructive">人机验证组件加载失败，请检查网络后刷新页面</p>;
    }
    return <div ref={containerRef} className="min-h-[65px]" />;
};

export default TurnstileWidget;
