import { omni } from '../services/onlineMusic/omni';
import { useOnlineProviderAccountStore } from '../stores/useOnlineProviderAccountStore';
import { isCapacitorAndroid } from './runtime';

// src/platform/restoreProviderAuth.ts
//
// 安卓端启动时的音源登录态恢复。
//
// 关键：**必须等后端就绪后再恢复**。bootstrap 极早期调用时，本地 Node 后端
// 还没起来（要解包/启动，需要几秒到几十秒），getLoginStatus 会失败 →
// 表现为"重启后 QQ/波点显示未登录"。
// 所以这里先轮询等后端有响应，再逐一恢复各音源的登录态。

/** 轮询等待本地后端就绪（最长 60 秒）。 */
const waitUntilBackendReady = async (): Promise<boolean> => {
  for (let i = 0; i < 30; i++) {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 3000);
      const response = await fetch('http://127.0.0.1:3100/', { signal: controller.signal });
      clearTimeout(timer);
      if (response.status < 500) return true;
    } catch {
      /* 后端还没起，继续等 */
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  return false;
};

export const restoreProviderAuth = async (): Promise<void> => {
  if (!isCapacitorAndroid()) return;
  console.log('[AuthRestore] 启动恢复流程开始');

  // 等后端就绪（v26 的教训：太早跑 getLoginStatus 必然失败且无声无息）
  const backendReady = await waitUntilBackendReady();
  if (!backendReady) {
    console.log('[AuthRestore] 等待 60 秒后端仍未就绪，放弃本次恢复');
    return;
  }
  console.log('[AuthRestore] 后端已就绪，开始恢复登录态');

  const tasks: Array<{ providerId: 'bodian' | 'qq'; label: string }> = [
    { providerId: 'bodian', label: '波点' },
    { providerId: 'qq', label: 'QQ 音乐' },
  ];

  for (const { providerId, label } of tasks) {
    try {
      const user = await omni.getLoginStatus(providerId);
      if (!user) {
        console.log(`[AuthRestore] ${label}: 未登录`);
        continue;
      }
      const store = useOnlineProviderAccountStore.getState();
      const existing = store.accounts[providerId]?.user;
      if (existing && String(existing.id) === String((user as { id?: unknown }).id)) {
        console.log(`[AuthRestore] ${label}: 账号状态已一致，跳过`);
        continue;
      }
      store.updateAccount(providerId, {
        status: 'authenticated',
        user: user as never,
        hydration: 'ready',
        freshness: 'fresh',
        error: undefined,
      });
      console.log(`[AuthRestore] ${label}: 已恢复登录账号`);
    } catch (error) {
      console.log(`[AuthRestore] ${label} 恢复失败:`, error instanceof Error ? error.message : error);
    }
  }
};
