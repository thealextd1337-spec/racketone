export const REMEMBER_DURATION_MS = 30 * 24 * 60 * 60 * 1000;
export const ADMIN_STORAGE_KEY = "advantly-support-auth-v1";

/** Nur bestätigte Sitzungen merken; Token-Erneuerung verlängert die feste Frist nicht. */
export function createSessionStorage({ storage, now = Date.now, key = ADMIN_STORAGE_KEY }) {
  const memory = new Map();
  const listeners = new Set();
  let deadline = null;

  function removeStored() {
    try { storage?.removeItem(key); } catch { /* Browser-Speicher kann gesperrt sein. */ }
  }

  function clear() {
    deadline = null;
    memory.clear();
    removeStored();
  }

  function expire() {
    if (deadline === null || now() < deadline) return false;
    clear();
    for (const listener of listeners) queueMicrotask(listener);
    return true;
  }

  function readStored() {
    try {
      const raw = storage?.getItem(key);
      if (!raw) return null;
      const record = JSON.parse(raw);
      if (!Number.isFinite(record.expiresAt) || record.expiresAt <= now()
        || record.expiresAt > now() + REMEMBER_DURATION_MS || typeof record.value !== "string") {
        removeStored();
        return null;
      }
      deadline = record.expiresAt;
      return record.value;
    } catch {
      removeStored();
      return null;
    }
  }

  const restored = readStored();
  if (restored !== null) memory.set(key, restored);

  return {
    getItem(name) {
      expire();
      if (name === key && deadline !== null) {
        const stored = readStored();
        if (stored !== null) memory.set(key, stored);
        else {
          clear();
          for (const listener of listeners) queueMicrotask(listener);
        }
      }
      return memory.get(name) ?? null;
    },
    setItem(name, value) {
      if (expire()) return;
      memory.set(name, value);
      if (name === key && deadline !== null) {
        storage.setItem(key, JSON.stringify({ expiresAt: deadline, value }));
      }
    },
    removeItem(name) {
      memory.delete(name);
      if (name === key) { deadline = null; removeStored(); }
    },
    remember() {
      const value = memory.get(key);
      if (!value || !storage) throw new Error("remember-unavailable");
      const expiresAt = now() + REMEMBER_DURATION_MS;
      storage.setItem(key, JSON.stringify({ expiresAt, value }));
      deadline = expiresAt;
    },
    isRemembered() { expire(); return deadline !== null; },
    onExpire(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    forget() { deadline = null; removeStored(); },
    expire,
    clear,
  };
}
