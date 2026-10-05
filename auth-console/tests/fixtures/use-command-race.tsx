import { createRoot } from 'react-dom/client';
import { useCommand } from '../../src/features/governance/shared/useCommand';

type Payload = { commandId: string };
const calls: Payload[] = [];
let settle: ((value: string) => void) | undefined;
let reject: ((reason: Error) => void) | undefined;
let builders = 0;
let hook: ReturnType<typeof useCommand<Payload, string>>;
const execute = (payload: Payload) => {
  calls.push(payload);
  return new Promise<string>((resolve, fail) => {
    settle = resolve;
    reject = fail;
  });
};
const build = (commandId: string) => {
  builders++;
  return { commandId };
};
function Fixture() {
  hook = useCommand(execute);
  return <output>{hook.busy ? 'BUSY' : hook.unknown ? 'UNKNOWN' : 'IDLE'}</output>;
}
// 使用真实React调度；两个send/reset发生于同一同步事件，不能靠等待下一次渲染规避竞态。
Object.assign(window, {
  commandRace: {
    double: () => {
      void hook.send(build);
      void hook.send(build);
    },
    resetAndRetry: () => {
      void hook.send(build);
      hook.reset();
      reject?.(new Error('receipt unavailable'));
    },
    retry: () => hook.send(build),
    resolve: () => settle?.('COMPLETED'),
    snapshot: () => ({ calls, builders, busy: hook.busy, unknown: hook.unknown }),
  },
});
createRoot(document.getElementById('root')!).render(<Fixture />);
