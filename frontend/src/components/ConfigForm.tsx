import React, { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { useToast } from '@/hooks/use-toast';
import type { ApiResponse } from '@/types';

export interface ConfigField {
  key: string;
  label: string;
  type: 'text' | 'textarea' | 'switch' | 'number';
  default: string;
  help?: string;
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
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    load()
      .then((res) => {
        if (res.code !== 200) throw new Error(res.message);
        const merged: Record<string, string> = {};
        fields.forEach((f) => {
          merged[f.key] = res.data?.[f.key] ?? f.default;
        });
        setValues(merged);
      })
      .catch((e) => toast({ title: '加载失败', description: e.message, variant: 'destructive' }))
      .finally(() => setLoading(false));
  }, [load, fields, toast]);

  const setValue = (k: string, v: string) => setValues((prev) => ({ ...prev, [k]: v }));

  const handleSave = async () => {
    setSaving(true);
    try {
      const res = await save(values);
      if (res.code !== 200) throw new Error(res.message);
      toast({ title: res.message || '已保存' });
    } catch (e: any) {
      toast({ title: '保存失败', description: e.message, variant: 'destructive' });
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
          ) : f.type === 'textarea' ? (
            <textarea
              id={f.key}
              className="w-full min-h-[100px] rounded-md border bg-background p-2 text-sm"
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
        </div>
      ))}
      <Button onClick={handleSave} disabled={saving}>
        {saving ? '保存中…' : '保存'}
      </Button>
    </div>
  );
};

export default ConfigForm;
