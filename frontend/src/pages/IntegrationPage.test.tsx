import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import { integrationService } from '@/api/integration';
import IntegrationPage from './IntegrationPage';

/**
 * 接入配置页的 Webhook 事件订阅。
 *
 * 此前界面只列了 2 个事件（转人工请求 / 会话结束），后端实际发 5 个；另外 3 个只能绕过界面调接口订阅，
 * 而使用手册与开放 API 参考都让用户「在接入配置页勾选」——照着做根本找不到选项。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));
vi.mock('@/stores/appStore', () => ({ useAppStore: () => ({ currentApp: { id: 20, name: '云舟商城客服' } }) }));
vi.mock('@/api/integration', () => ({
    integrationService: {
        getApp: vi.fn(),
        updateWebhook: vi.fn(),
        regenerateSecret: vi.fn(),
        regenerateWebhookSecret: vi.fn(),
        updateRateLimit: vi.fn(),
        updateAllowedOrigins: vi.fn(),
        sendWebhookTest: vi.fn(),
    },
}));

const res = <T,>(code: number, data: T, message = 'success'): ApiResponse<T> =>
    ({ code, message, data, timestamp: '2026-09-25T00:00:00' });

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(integrationService.getApp).mockReset().mockResolvedValue(res(200, {
        id: 20, appKey: 'app_x', webhookUrl: 'https://recv.example.com/hook', webhookEvents: 'session.ended',
    } as never));
    vi.mocked(integrationService.updateWebhook).mockReset();
});

describe('IntegrationPage Webhook 事件订阅', () => {
    it('列出后端实际发布的全部 5 个事件，且回显已订阅项', async () => {
        render(<IntegrationPage />);
        await flush();

        for (const ev of ['session.handover_requested', 'session.accepted', 'agent.replied', 'session.returned_to_ai', 'session.ended']) {
            expect(screen.getByText(new RegExp(ev.replace('.', '\\.')))).toBeInTheDocument();
        }
        expect(screen.getByRole('checkbox', { name: /session\.ended/ })).toBeChecked();
        expect(screen.getByRole('checkbox', { name: /agent\.replied/ })).not.toBeChecked();
    });

    it('勾选新事件后保存，提交的订阅串同时包含原有与新增事件', async () => {
        vi.mocked(integrationService.updateWebhook).mockResolvedValue(res(200, {} as never));
        render(<IntegrationPage />);
        await flush();

        fireEvent.click(screen.getByRole('checkbox', { name: /agent\.replied/ }));
        fireEvent.click(screen.getByRole('button', { name: '保存 Webhook' }));
        await flush();

        const payload = vi.mocked(integrationService.updateWebhook).mock.calls[0][1];
        expect(payload.webhookEvents.split(',').sort()).toEqual(['agent.replied', 'session.ended']);
        expect(toastSpy).toHaveBeenCalledWith({ title: '已保存' });
    });

    it('保存返回业务错误时弹 destructive toast', async () => {
        vi.mocked(integrationService.updateWebhook).mockResolvedValue(res(400, null as never, '回调地址不能指向内网'));
        render(<IntegrationPage />);
        await flush();

        fireEvent.click(screen.getByRole('button', { name: '保存 Webhook' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive', description: '回调地址不能指向内网' }));
    });
});
