import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useSePayPolling } from './features/payments/SePayParts';

afterEach(()=>{vi.useRealTimers();vi.restoreAllMocks();});
function fixture(summary){
  vi.useFakeTimers();let hidden=false;
  vi.spyOn(document,'hidden','get').mockImplementation(()=>hidden);
  const client={summary},lock={current:false},onSummary=vi.fn();
  const hook=renderHook(()=>useSePayPolling({active:true,client,id:'101',lock,onSummary}));
  return {...hook,lock,onSummary,hide(value){hidden=value;document.dispatchEvent(new Event('visibilitychange'));}};
}
const hanging=(_id,signal)=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')),{once:true}));
it('aborts a stalled status read and releases the manual refresh lock',async()=>{
  const summary=vi.fn(hanging),f=fixture(summary);
  await act(()=>vi.advanceTimersByTimeAsync(5000));expect(f.lock.current).toBe(true);
  await act(()=>vi.advanceTimersByTimeAsync(10001));expect(f.lock.current).toBe(false);expect(f.result.current).toMatch(/Chưa cập nhật/);f.unmount();
});
it('hiding and showing while abort settles leaves one timer chain and cleans up on navigation',async()=>{
  const summary=vi.fn().mockImplementationOnce(hanging).mockResolvedValue({items:[]}),f=fixture(summary);
  await act(()=>vi.advanceTimersByTimeAsync(5000));
  await act(async()=>{f.hide(true);f.hide(false);});expect(f.lock.current).toBe(false);expect(vi.getTimerCount()).toBe(1);
  await act(()=>vi.advanceTimersByTimeAsync(5000));expect(summary).toHaveBeenCalledTimes(2);expect(vi.getTimerCount()).toBe(1);f.unmount();expect(vi.getTimerCount()).toBe(0);
});
it('stops after fifteen minutes instead of polling forever',async()=>{
  const summary=vi.fn().mockResolvedValue({items:[]}),f=fixture(summary);
  await act(()=>vi.advanceTimersByTimeAsync(15*60*1000));const count=summary.mock.calls.length;
  await act(()=>vi.advanceTimersByTimeAsync(60000));expect(summary).toHaveBeenCalledTimes(count);expect(f.result.current).toMatch(/tạm dừng/);f.unmount();
});
