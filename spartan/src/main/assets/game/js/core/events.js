/** Tiny synchronous event bus shared by every module through `ctx.events`. */
export class EventBus {
  constructor() {
    this.handlers = new Map();
  }

  /** Subscribes `fn` to `name` and returns a function that unsubscribes it. */
  on(name, fn) {
    let list = this.handlers.get(name);
    if (!list) this.handlers.set(name, (list = []));
    list.push(fn);
    return () => this.off(name, fn);
  }

  once(name, fn) {
    const off = this.on(name, (payload) => {
      off();
      fn(payload);
    });
    return off;
  }

  off(name, fn) {
    const list = this.handlers.get(name);
    if (!list) return;
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }

  /** Calls every handler in subscription order. A throwing handler doesn't stop the others. */
  emit(name, payload) {
    const list = this.handlers.get(name);
    if (!list || list.length === 0) return;
    for (const fn of list.slice()) {
      try {
        fn(payload);
      } catch (err) {
        console.error(`event "${name}" handler failed`, err);
        if (typeof window !== 'undefined' && window.__game) window.__game.errors.push(String(err && err.stack || err));
      }
    }
  }
}
