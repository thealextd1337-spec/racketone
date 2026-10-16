import {
  buildTicketUpdateArgs,
  createRequestEpoch,
  makeMessageOperation,
  SupportError,
  validateMessage,
} from "./domain.js";

function messageId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  if (!globalThis.crypto?.getRandomValues) throw new SupportError("support_unavailable");
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0"));
  return [hex.slice(0, 4).join(""), hex.slice(4, 6).join(""), hex.slice(6, 8).join(""), hex.slice(8, 10).join(""), hex.slice(10).join("")].join("-");
}

function initialState(config) {
  return {
    language: "en",
    phase: config?.ok ? "login" : "missing-config",
    configReason: config?.ok ? null : config?.reason ?? "invalid-url",
    authError: null,
    loginEmail: "",
    busy: false,
    email: "",
    factorId: null,
    qrCode: null,
    secret: null,
    aal2: false,
    tickets: [],
    members: [],
    listLoading: false,
    listError: null,
    search: "",
    statusFilter: null,
    selectedId: null,
    ticket: null,
    detailLoading: false,
    detailError: null,
    detailNotice: null,
    updateDraft: null,
    saving: false,
    publicConfirmation: null,
  };
}

function noticeForError(error, fallback = "genericError") {
  switch (error?.code) {
    case "resolution-version-required": return "bugResolutionRequired";
    case "support_conflict": return "conflict";
    case "support_invalid": return "invalidFields";
    case "support_not_found": return "notFound";
    case "support_guest_reply_unavailable": return "guestPublicReplyUnavailable";
    case "message-required": return "messageRequired";
    case "message-too-long": return "messageTooLong";
    case "update-reason-required": return "updateReasonRequired";
    case "update-reason-too-long": return "updateReasonTooLong";
    default: return fallback;
  }
}

function composerState(map, ticketId) {
  if (!map.has(ticketId)) map.set(ticketId, { body: "", mode: "internal", error: null });
  return map.get(ticketId);
}

function isOwnedTicket(ticket, ticketId) {
  return ticket?.id === ticketId && ticket.owner_id !== null && ticket.owner_id !== undefined;
}

function updateDraftFor(ticket) {
  return {
    status: ticket.status,
    priority: ticket.priority,
    assignee: ticket.assignee ?? "",
    relatedId: ticket.related_id ?? "",
    resolutionVersion: ticket.resolution_version ?? "",
    reason: "",
  };
}

export class AdminController {
  constructor({ config, auth, api, view }) {
    this.config = config;
    this.auth = auth;
    this.api = api;
    this.view = view;
    this.state = initialState(config);
    this.sessionEpoch = createRequestEpoch();
    this.listEpoch = createRequestEpoch();
    this.detailEpoch = createRequestEpoch();
    this.authFlowEpoch = createRequestEpoch();
    this.authQueue = Promise.resolve();
    this.authOperations = 0;
    this.sessionToken = null;
    this.composers = new Map();
    this.pendingMessages = new Map();
    this.searchTimer = null;
    this.listeners = [];
    this.subscribe();
    this.render();
  }

  subscribe() {
    const routes = {
      language: (payload) => this.setLanguage(payload?.language),
      "sign-in": (payload) => this.signIn(payload?.email, payload?.password),
      "verify-totp": (payload) => this.verifyTotp(payload?.code),
      "sign-out": () => this.signOut(),
      refresh: () => this.refresh(),
      search: (payload) => this.setSearch(payload?.value),
      "status-filter": (payload) => this.setStatusFilter(payload?.value),
      "select-ticket": (payload) => this.selectTicket(payload?.id),
      "back-to-list": () => this.backToList(),
      "retry-ticket": () => this.state.selectedId && this.loadTicket(this.state.selectedId),
      "ticket-draft": (payload) => this.setTicketDraft(payload?.fields),
      "save-ticket": (payload) => this.saveTicket(payload?.fields),
      composer: (payload) => this.setComposer(payload),
      "send-message": () => this.sendMessage(),
      "confirm-public-send": () => this.confirmPublicSend(),
      "cancel-public-send": () => this.cancelPublicSend(),
      "retry-message": () => this.retryMessage(),
      "discard-message": () => this.discardMessage(),
      retry: () => this.refresh(),
    };
    for (const [event, callback] of Object.entries(routes)) {
      this.view.on(event, callback);
      this.listeners.push([event, callback]);
    }
    this.unsubscribeAuth = this.auth?.onAuthStateChange?.(() => {
      if (this.state.phase !== "login" && this.state.phase !== "missing-config") {
        void this.clearSession("sessionExpired");
      }
    });
  }

  render() {
    this.view.render(this.state, {
      composers: this.composers,
      pendingMessages: this.pendingMessages,
    });
  }

  setLanguage(language) {
    if (language !== "en" && language !== "de") return;
    this.state.language = language;
    this.render();
  }

  async signIn(email, password) {
    if (!this.config?.ok || !this.auth || this.state.busy || this.state.phase !== "login") return;
    const flow = this.authFlowEpoch.next();
    this.authOperations += 1;
    this.state.busy = true;
    this.state.authError = null;
    this.state.loginEmail = String(email ?? "").trim();
    this.render();
    try {
      const result = await this.runAuthFlow(flow, () => this.auth.signIn(this.state.loginEmail, password));
      if (!this.authFlowEpoch.isCurrent(flow) || this.state.phase !== "login" || !result) return;
      this.state.email = result.email;
      if (result.kind === "unlocked" && result.aal === "aal2") {
        await this.openInbox(flow, "login");
      } else {
        this.state.factorId = result.factorId;
        this.state.qrCode = result.kind === "setup" ? result.qrCode : null;
        this.state.secret = result.kind === "setup" ? result.secret : null;
        this.state.phase = result.kind === "setup" ? "mfa-setup" : "mfa-challenge";
        this.state.busy = false;
        this.render();
      }
    } catch {
      if (!this.authFlowEpoch.isCurrent(flow) || this.state.phase !== "login") return;
      this.state.phase = "login";
      this.state.busy = false;
      this.state.authError = "signInError";
      this.render();
    } finally {
      this.authOperations -= 1;
    }
  }

  async verifyTotp(code) {
    if (this.state.busy || !["mfa-setup", "mfa-challenge"].includes(this.state.phase)) return;
    const flow = this.authFlowEpoch.next();
    const expectedPhase = this.state.phase;
    this.authOperations += 1;
    this.state.busy = true;
    this.state.authError = null;
    this.render();
    try {
      const result = await this.runAuthFlow(flow, () => this.auth.verifyTotp(this.state.factorId, code));
      if (!this.authFlowEpoch.isCurrent(flow) || this.state.phase !== expectedPhase || !result) return;
      if (result?.aal !== "aal2") throw new SupportError("support_mfa_required");
      await this.openInbox(flow, expectedPhase);
    } catch (error) {
      if (!this.authFlowEpoch.isCurrent(flow) || this.state.phase !== expectedPhase) return;
      this.state.busy = false;
      if (error?.code === "support_mfa_required") {
        await this.clearSession("mfaRequired");
        return;
      }
      this.state.authError = "mfaError";
      this.render();
    } finally {
      this.authOperations -= 1;
    }
  }

  enqueueAuth(action) {
    const previous = this.authQueue;
    let release;
    this.authQueue = new Promise((resolve) => { release = resolve; });
    return (async () => {
      await previous;
      try {
        return await action();
      } finally {
        release();
      }
    })();
  }

  async runAuthFlow(flow, action) {
    return this.enqueueAuth(async () => {
      if (!this.authFlowEpoch.isCurrent(flow)) return null;
      try {
        const result = await action();
        if (!this.authFlowEpoch.isCurrent(flow)) {
          await this.auth?.signOut?.();
          return null;
        }
        return result;
      } catch (error) {
        if (!this.authFlowEpoch.isCurrent(flow)) {
          await this.auth?.signOut?.();
          return null;
        }
        throw error;
      }
    });
  }

  async openInbox(flow, expectedPhase) {
    if (!this.authFlowEpoch.isCurrent(flow) || this.state.phase !== expectedPhase) return;
    this.state.aal2 = true;
    this.state.phase = "inbox";
    this.state.busy = false;
    this.state.authError = null;
    this.state.qrCode = null;
    this.state.secret = null;
    this.sessionToken = this.sessionEpoch.next();
    this.render();
    await this.loadInbox();
  }

  hasAccess() {
    return this.state.phase === "inbox" && this.state.aal2 && this.sessionToken !== null && this.sessionEpoch.isCurrent(this.sessionToken);
  }

  async refresh() {
    if (!this.hasAccess()) return;
    const ticketId = this.state.selectedId;
    await Promise.all([
      this.loadInbox(),
      ticketId ? this.loadTicket(ticketId, { preserveNotice: true }) : Promise.resolve(),
    ]);
  }

  async loadInbox() {
    if (!this.hasAccess()) return;
    const request = this.listEpoch.next();
    const session = this.sessionToken;
    this.state.listLoading = true;
    this.state.listError = null;
    this.render();
    try {
      const search = this.state.search.trim() || null;
      const [tickets, members] = await Promise.all([
        this.api.listTickets({ status: this.state.statusFilter, search }),
        this.api.listMembers(),
      ]);
      if (!this.listEpoch.isCurrent(request) || !this.sessionEpoch.isCurrent(session)) return;
      if (!Array.isArray(tickets) || !Array.isArray(members)) throw new SupportError("support_unavailable");
      this.state.tickets = tickets;
      this.state.members = members;
      this.state.listLoading = false;
      this.render();
    } catch (error) {
      if (!this.listEpoch.isCurrent(request) || !this.sessionEpoch.isCurrent(session)) return;
      this.state.listLoading = false;
      if (this.handleAccessError(error)) return;
      this.state.listError = noticeForError(error, "listError");
      this.render();
    }
  }

  async selectTicket(id) {
    if (!this.hasAccess() || typeof id !== "string" || !id) return;
    if (id === this.state.selectedId && this.state.ticket) return;
    this.state.selectedId = id;
    this.state.ticket = null;
    this.state.detailLoading = true;
    this.state.detailError = null;
    this.state.detailNotice = null;
    this.state.updateDraft = null;
    const request = this.detailEpoch.next();
    const session = this.sessionToken;
    this.render();
    await this.fetchTicket(id, request, session);
  }

  async loadTicket(id, { preserveNotice = false, preserveReason = false } = {}) {
    if (!this.hasAccess() || id !== this.state.selectedId) return;
    const request = this.detailEpoch.next();
    const session = this.sessionToken;
    const currentNotice = preserveNotice ? this.state.detailNotice : null;
    const currentReason = preserveReason ? this.state.updateDraft?.reason ?? "" : "";
    this.state.detailLoading = true;
    this.state.detailError = null;
    if (!preserveNotice) this.state.detailNotice = null;
    this.render();
    await this.fetchTicket(id, request, session, currentNotice, currentReason);
  }

  async fetchTicket(id, request, session, preservedNotice = null, preservedReason = "") {
    try {
      const ticket = await this.api.getTicket(id);
      if (!this.detailEpoch.isCurrent(request) || !this.sessionEpoch.isCurrent(session) || this.state.selectedId !== id) return;
      if (!ticket || ticket.id !== id) throw new SupportError("support_not_found");
      this.state.ticket = ticket;
      this.state.updateDraft = { ...updateDraftFor(ticket), reason: preservedReason };
      this.state.detailLoading = false;
      this.state.detailNotice = preservedNotice;
      this.render();
    } catch (error) {
      if (!this.detailEpoch.isCurrent(request) || !this.sessionEpoch.isCurrent(session) || this.state.selectedId !== id) return;
      this.state.detailLoading = false;
      if (this.handleAccessError(error)) return;
      this.state.detailError = noticeForError(error, "genericError");
      this.state.detailNotice = preservedNotice;
      this.render();
    }
  }

  backToList() {
    if (!this.hasAccess()) return;
    this.state.selectedId = null;
    this.state.ticket = null;
    this.state.detailLoading = false;
    this.state.detailError = null;
    this.state.detailNotice = null;
    this.state.updateDraft = null;
    this.detailEpoch.invalidate();
    this.render();
  }

  setSearch(value) {
    if (!this.hasAccess()) return;
    this.state.search = String(value ?? "").slice(0, 120);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => void this.loadInbox(), 250);
  }

  setStatusFilter(value) {
    if (!this.hasAccess()) return;
    this.state.statusFilter = value || null;
    void this.loadInbox();
  }

  setTicketDraft(fields) {
    if (!this.hasAccess() || !this.state.updateDraft || !fields) return;
    this.state.updateDraft = { ...this.state.updateDraft, ...fields };
  }

  async saveTicket(fields) {
    if (!this.hasAccess() || this.state.saving || !this.state.ticket) return;
    if (fields && this.state.updateDraft) this.state.updateDraft = { ...this.state.updateDraft, ...fields };
    let args;
    try {
      args = buildTicketUpdateArgs(this.state.ticket, fields ?? this.state.updateDraft);
    } catch (error) {
      this.state.detailNotice = noticeForError(error, "invalidFields");
      this.render();
      return;
    }
    const ticketId = this.state.ticket.id;
    const session = this.sessionToken;
    this.state.saving = true;
    this.state.detailNotice = null;
    this.render();
    try {
      const updated = await this.api.updateTicket(args);
      if (!this.sessionEpoch.isCurrent(session)) return;
      if (this.state.selectedId !== ticketId) {
        this.state.saving = false;
        this.render();
        return;
      }
      this.state.ticket = updated;
      this.state.updateDraft = updateDraftFor(updated);
      this.state.detailNotice = "saved";
      this.state.saving = false;
      this.patchListTicket(updated);
      this.render();
    } catch (error) {
      if (!this.sessionEpoch.isCurrent(session)) return;
      this.state.saving = false;
      if (this.handleAccessError(error)) return;
      if (this.state.selectedId !== ticketId) {
        this.render();
        return;
      }
      if (error?.code === "support_conflict") {
        this.state.detailNotice = "conflict";
        this.render();
        await this.loadTicket(ticketId, { preserveNotice: true, preserveReason: true });
        return;
      }
      this.state.detailNotice = noticeForError(error, "genericError");
      this.render();
    }
  }

  patchListTicket(ticket) {
    this.state.tickets = this.state.tickets.map((item) => item.id === ticket.id ? {
      ...item,
      category: ticket.category,
      title: ticket.title,
      status: ticket.status,
      priority: ticket.priority,
      version: ticket.version,
      updated_at: ticket.updated_at,
    } : item);
  }

  setComposer(payload) {
    if (!this.hasAccess() || !this.state.selectedId || this.pendingMessages.has(this.state.selectedId)) return;
    const current = composerState(this.composers, this.state.selectedId);
    const previousMode = current.mode;
    if (typeof payload?.body === "string") current.body = payload.body.slice(0, 4000);
    if (payload?.mode === "internal" || payload?.mode === "public") current.mode = payload.mode;
    current.error = null;
    if (current.mode !== previousMode) this.render();
  }

  async sendMessage() {
    if (!this.hasAccess() || !this.state.selectedId || !this.state.ticket) return;
    const ticketId = this.state.selectedId;
    if (this.pendingMessages.has(ticketId)) return;
    const composer = composerState(this.composers, ticketId);
    const body = String(composer.body ?? "").trim();
    const error = validateMessage(body);
    if (error) {
      composer.error = error;
      this.render();
      return;
    }
    if (composer.mode === "public") {
      if (this.rejectUnavailablePublicReply(ticketId, composer.body)) return;
      this.state.publicConfirmation = { ticketId, body };
      this.view.showPublicConfirmation?.(body);
      return;
    }
    await this.startMessage(ticketId, body, true);
  }

  async confirmPublicSend() {
    const confirmation = this.state.publicConfirmation;
    this.state.publicConfirmation = null;
    this.view.hidePublicConfirmation?.();
    if (!confirmation || !this.hasAccess() || confirmation.ticketId !== this.state.selectedId) return;
    if (this.rejectUnavailablePublicReply(confirmation.ticketId, confirmation.body)) return;
    await this.startMessage(confirmation.ticketId, confirmation.body, false);
  }

  cancelPublicSend() {
    this.state.publicConfirmation = null;
    this.view.hidePublicConfirmation?.();
  }

  async startMessage(ticketId, body, internal) {
    if (!this.hasAccess() || ticketId !== this.state.selectedId || this.pendingMessages.has(ticketId)) return;
    if (!internal && this.rejectUnavailablePublicReply(ticketId, body)) return;
    let operation;
    try {
      operation = makeMessageOperation(ticketId, body, internal, messageId);
    } catch (error) {
      const composer = composerState(this.composers, ticketId);
      composer.error = error?.code;
      this.render();
      return;
    }
    this.pendingMessages.set(ticketId, { operation, status: "queued", error: null });
    this.render();
    await this.transmitMessage(ticketId);
  }

  rejectUnavailablePublicReply(ticketId, body) {
    if (isOwnedTicket(this.state.ticket, ticketId)) return false;
    const composer = composerState(this.composers, ticketId);
    if (!String(composer.body ?? "").length && typeof body === "string") composer.body = body;
    composer.mode = "internal";
    composer.error = "guestPublicReplyUnavailable";
    this.render();
    return true;
  }

  async transmitMessage(ticketId) {
    if (!this.hasAccess()) return;
    const pending = this.pendingMessages.get(ticketId);
    if (!pending || pending.status === "sending") return;
    const session = this.sessionToken;
    pending.status = "sending";
    pending.error = null;
    this.render();
    try {
      const ticket = await this.api.sendMessage(pending.operation.args);
      if (!this.sessionEpoch.isCurrent(session)) return;
      this.pendingMessages.delete(ticketId);
      if (this.state.selectedId !== ticketId) {
        if (ticket && ticket.id === ticketId) this.patchListTicket(ticket);
        this.render();
        return;
      }
      this.composers.set(ticketId, { body: "", mode: "internal", error: null });
      if (ticket && ticket.id === ticketId) {
        this.state.ticket = ticket;
        this.state.updateDraft = updateDraftFor(ticket);
        this.patchListTicket(ticket);
      }
      this.state.detailNotice = null;
      this.state.detailError = null;
      this.render();
    } catch (error) {
      if (!this.sessionEpoch.isCurrent(session)) return;
      const current = this.pendingMessages.get(ticketId);
      if (current?.operation !== pending.operation) return;
      current.status = "error";
      current.error = noticeForError(error, "genericError");
      if (this.handleAccessError(error)) return;
      if (this.state.selectedId !== ticketId) {
        this.render();
        return;
      }
      if (error?.code === "support_conflict") {
        this.state.detailNotice = "conflict";
        this.render();
        await this.loadTicket(ticketId, { preserveNotice: true });
      } else {
        this.render();
      }
    }
  }

  async retryMessage() {
    const ticketId = this.state.selectedId;
    if (!this.hasAccess() || !ticketId) return;
    const pending = this.pendingMessages.get(ticketId);
    if (!pending || pending.status === "sending") return;
    await this.transmitMessage(ticketId);
  }

  discardMessage() {
    const ticketId = this.state.selectedId;
    if (!ticketId) return;
    const pending = this.pendingMessages.get(ticketId);
    if (!pending || pending.status === "sending") return;
    this.pendingMessages.delete(ticketId);
    this.composers.set(ticketId, { body: "", mode: "internal", error: null });
    this.render();
  }

  handleAccessError(error) {
    if (["support_forbidden", "support_mfa_required", "session_expired"].includes(error?.code)) {
      const messageKey = error.code === "support_forbidden" ? "noAccess" : error.code === "support_mfa_required" ? "mfaRequired" : "sessionExpired";
      void this.clearSession(messageKey);
      return true;
    }
    return false;
  }

  async signOut() {
    await this.clearSession(null);
  }

  async clearSession(messageKey) {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = null;
    this.sessionEpoch.invalidate();
    this.listEpoch.invalidate();
    this.detailEpoch.invalidate();
    this.authFlowEpoch.invalidate();
    const hadSession = this.state.phase !== "login" && this.state.phase !== "missing-config";
    const language = this.state.language;
    this.sessionToken = null;
    this.composers.clear();
    this.pendingMessages.clear();
    this.state = initialState(this.config);
    this.state.language = language;
    this.state.authError = messageKey;
    this.view.hidePublicConfirmation?.();
    this.render();
    if (hadSession || this.authOperations > 0) {
      await this.enqueueAuth(() => this.auth?.signOut?.());
    }
  }

  dispose() {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.unsubscribeAuth?.();
    for (const [event, callback] of this.listeners) this.view.off?.(event, callback);
  }
}
