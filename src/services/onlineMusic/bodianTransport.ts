import { OnlineProviderError } from '../../types/onlineMusic';
import type { BodianOperation, BodianParams, BodianResult } from 'bodian-music-api';

// src/services/onlineMusic/bodianTransport.ts

export type { BodianOperation, BodianParams } from 'bodian-music-api';
export type BodianBridgeResult = BodianResult;

/**
 * 取出可用的波点请求通道。
 *
 * 优先 Electron 主进程的桥；电容端（安卓）由 platform/capacitorBodianBridge.ts 在
 * **独立全局** `__foliaBodianRequest` 注入同签名实现。
 * 之所以不挂到 window.electron：App 里大量 `if (window.electron)` 守卫会因此误判成桌面端，
 * 进而调用不存在的方法抛异常（表现过"一打开设置就黑屏"）。
 */
const resolveBodianRequest = () => {
    if (typeof window === 'undefined') return null;

    const fromElectron = window.electron?.bodianRequest;
    if (typeof fromElectron === 'function') return fromElectron;

    const fromCapacitor = (window as typeof window & { __foliaBodianRequest?: unknown })
        .__foliaBodianRequest;
    return typeof fromCapacitor === 'function' ? fromCapacitor : null;
};

export const getBodianTransportAvailability = () => (
    resolveBodianRequest()
        ? { configured: true } as const
        : { configured: false, reason: 'runtime-unavailable' } as const
);

export async function requestBodian<T = unknown>(operation: BodianOperation, params: BodianParams = {}): Promise<T> {
    if (!getBodianTransportAvailability().configured) {
        throw new OnlineProviderError('unavailable', 'Bodian requires the desktop app', 'bodian');
    }
    let result: BodianBridgeResult;
    // Omni consumers request 150/1000-row batches; Bodian serves at most 100 and returns its own cursor.
    const boundedParams = Number.isSafeInteger(params.limit) && Number(params.limit) > 100
        ? { ...params, limit: 100 } : params;
    const bodianRequest = resolveBodianRequest();
    if (!bodianRequest) {
        throw new OnlineProviderError('unavailable', 'Bodian request channel is not available', 'bodian');
    }
    try { result = await bodianRequest(operation, boundedParams); }
    catch { throw new OnlineProviderError('network', 'Bodian desktop request failed', 'bodian'); }
    if (!result.ok) throw new OnlineProviderError(result.error.code, result.error.message, 'bodian');
    return result.data as T;
}
