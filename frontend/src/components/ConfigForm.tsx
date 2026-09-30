import React, { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useToast } from '@/hooks/use-toast';
import type { ApiResponse } from '@/types';
import { getErrorMessage } from '@/lib/apiResult';

export interface ConfigField {
  key: string;
  label: string;
  /**
   * secret：凭据类字段，后端只写不读——读到的是掩码或空串，输入框从不回显，留空提交即「不修改」
   */
  type: 'text' | 'textarea' | 'switch' | 'number' | 'select' | 'secret';
  default: string;
  help?: string;
  /** type 为 select 时的选项 */
  options?: { value: string; label: string }[];
  /** 紧跟在该字段之后渲染的附加内容，拿到的是表单当前值（含未保存的修改），如「测试配置」按钮 */
  renderAfter?: (values: Record<string, string>) => React.ReactNode;
}

interface ConfigFormProps {
  title: string;
  fields: ConfigField[];
  load: () => Promise<ApiResponse<Record<string, string>>>;
  save: (values: Record<string, string>) => Promise<ApiResponse<void>>;
}

const ConfigForm: React.FC<ConfigFormProps> = ({ title, fields, load, save }) => {
  const { toast } = useToast();
  const [values, setValues] = useState<Record<string, string>>({});
  const [secretConfigured, setSecretConfigured] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    load()
      .then((res) => {
        if (res.code !== 200) throw new Error(res.message);
        const merged: Record<string, string> = {};
        const secretSet: Record<string, boolean> = {};
        fields.forEach((f) => {
          if (f.type === 'secret') {
            secretSet[f.key] = !!res.data?.[f.key];
            merged[f.key] = '';
          } else {
            merged[f.key] = res.data?.[f.key] ?? f.default;
          }
        });
        setValues(merged);
        setSecretConfigured(secretSet);
      })
      .catch((e) => toast({ title: '加载失败', description: getErrorMessage(e), variant: 'destructive' }))
      .finally(() => setLoading(false));
  }, [load, fields, toast]);

  const setValue = (k: string, v: string) => setValues((prev) => ({ ...prev, [k]: v }));

  const handleSave = async () => {
    setSaving(true);
    try {
      const res = await save(values);
      if (res.code !== 200) throw new Error(res.message);
      toast({ title: res.message || '已保存' });
      // 刚填的凭据已落库：清空输入框、改成「已设置」，与重新打开页面时看到的一致
      const saved = fields.filter((f) => f.type === 'secret' && values[f.key]);
      if (saved.length > 0) {
        setSecretConfigured((prev) => ({ ...prev, ...Object.fromEntries(saved.map((f) => [f.key, true])) }));
        setValues((prev) => ({ ...prev, ...Object.fromEntries(saved.map((f) => [f.key, ''])) }));
      }
    } catch (e: unknown) {
      toast({ title: '保存失败', description: getErrorMessage(e), variant: 'destructive' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div className="p-6 text-muted-foreground">加载中…</div>;

  return (
    <div className="p-6 max-w-2xl space-y-6 overflow-y-auto h-full">
      <h3 className="text-lg font-semibold">{title}</h3>
      {fields.map((f) => (
        <div key={f.key} className="space-y-2">
          <label htmlFor={f.key} className="text-sm font-medium">{f.label}</label>
          {f.type === 'switch' ? (
            <div>
              <Switch
                id={f.key}
                checked={values[f.key] === 'true'}
                onCheckedChange={(c) => setValue(f.key, c ? 'true' : 'false')}
              />
            </div>
          ) : f.type === 'select' ? (
            <Select value={values[f.key] ?? f.default} onValueChange={(v) => setValue(f.key, v)}>
              <SelectTrigger id={f.key}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {f.options?.map((o) => (
                  <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : f.type === 'secret' ? (
            <Input
              id={f.key}
              type="password"
              autoComplete="new-password"
              placeholder={secretConfigured[f.key] ? '已设置，留空则不修改' : '未设置'}
              value={values[f.key] ?? ''}
              onChange={(e) => setValue(f.key, e.target.value)}
            />
          ) : f.type === 'textarea' ? (
            <Textarea
              id={f.key}
              className="min-h-[100px]"
              value={values[f.key] ?? ''}
              onChange={(e) => setValue(f.key, e.target.value)}
            />
          ) : (
            <Input
              id={f.key}
              type={f.type === 'number' ? 'number' : 'text'}
              value={values[f.key] ?? ''}
              onChange={(e) => setValue(f.key, e.target.value)}
            />
          )}
          {f.help && <p className="text-xs text-muted-foreground">{f.help}</p>}
          {f.renderAfter?.(values)}
        </div>
      ))}
      <Button onClick={handleSave} disabled={saving}>
        {saving ? '保存中…' : '保存'}
      </Button>
    </div>
  );
};

export default ConfigForm;
