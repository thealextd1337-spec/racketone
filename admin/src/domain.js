const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const STATUSES = new Set(["new", "reviewing", "planned", "resolved", "closed"]);
const PRIORITIES = new Set(["low", "normal", "high"]);

export class SupportError extends Error {
  constructor(code) {
    super(code);
    this.name = "SupportError";
    this.code = code;
  }
}

export function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function validateTicketUpdate(ticket, fields) {
  if (!ticket || !fields || !STATUSES.has(fields.status) || !PRIORITIES.has(fields.priority)) return "support_invalid";
  if (fields.status === "resolved" && ticket.category === "bug" && !String(fields.resolutionVersion ?? "").trim()) return "resolution-version-required";
  if (String(fields.resolutionVersion ?? "").trim().length > 80) return "support_invalid";
  if (fields.assignee && !isUuid(fields.assignee)) return "support_invalid";
  if (fields.relatedId && (!isUuid(fields.relatedId) || fields.relatedId === ticket.id)) return "support_invalid";
  if (!Number.isSafeInteger(Number(ticket.version)) || Number(ticket.version) < 0) return "support_invalid";
  const reason = String(fields.reason ?? "").trim();
  if (!reason) return "update-reason-required";
  if (Array.from(reason).length > 500) return "update-reason-too-long";
  return null;
}

export function buildTicketUpdateArgs(ticket, fields) {
  const validationError = validateTicketUpdate(ticket, fields);
  if (validationError) throw new SupportError(validationError);
  return {
    p_id: ticket.id,
    p_status: fields.status,
    p_priority: fields.priority,
    p_assignee: fields.assignee || null,
    p_related_id: fields.relatedId || null,
    p_resolution_version: String(fields.resolutionVersion ?? "").trim() || null,
    p_expected_version: Number(ticket.version),
    p_reason: String(fields.reason).trim(),
  };
}

export function validateMessage(body) {
  const trimmed = String(body ?? "").trim();
  if (!trimmed) return "message-required";
  if (Array.from(trimmed).length > 4000) return "message-too-long";
  return null;
}

export function makeMessageOperation(ticketId, body, internal, idGenerator) {
  if (!isUuid(ticketId) || typeof internal !== "boolean") throw new SupportError("support_invalid");
  const trimmed = String(body ?? "").trim();
  const validationError = validateMessage(trimmed);
  if (validationError) throw new SupportError(validationError);
  const id = idGenerator();
  if (!isUuid(id)) throw new SupportError("support_invalid");
  return Object.freeze({
    ticketId,
    messageId: id,
    body: trimmed,
    internal,
    args: Object.freeze({ p_id: ticketId, p_message_id: id, p_body: trimmed, p_internal: internal }),
  });
}

// Kontosuche: genau eine UUID, ein Freundescode oder ein Alias; die Auswertung macht ausschließlich der Server.
export function validateAccountQuery(query) {
  const trimmed = String(query ?? "").trim();
  if (!trimmed) return "account-query-required";
  if (trimmed.length > 120) return "support_invalid";
  return null;
}

const ACCOUNT_ACTIONS = {
  suspend: "admin_account_set_suspended",
  unsuspend: "admin_account_set_suspended",
  revoke: "admin_account_revoke_devices",
  "cancel-deletion": "admin_account_cancel_deletion",
};

// Zustandsändernde Kontoaktion: Begründung wie bei Vorgängen, optionaler Ticketbeleg nur für die Löschrücknahme.
export function buildAccountAction(account, action, fields) {
  const rpc = ACCOUNT_ACTIONS[action];
  if (!rpc || !isUuid(account?.profileId)) throw new SupportError("support_invalid");
  const reason = String(fields?.reason ?? "").trim();
  if (!reason) throw new SupportError("update-reason-required");
  if (Array.from(reason).length > 500) throw new SupportError("update-reason-too-long");
  const ticketId = String(fields?.ticketId ?? "").trim();
  if (ticketId && (action !== "cancel-deletion" || !isUuid(ticketId))) throw new SupportError("account-ticket-invalid");
  const args = { p_profile_id: account.profileId, p_reason: reason };
  if (rpc === "admin_account_set_suspended") args.p_suspended = action === "suspend";
  if (action === "cancel-deletion") args.p_ticket_id = ticketId || null;
  return Object.freeze({ rpc, args: Object.freeze(args) });
}

export function createRequestEpoch() {
  let current = 0;
  return Object.freeze({
    next() {
      current += 1;
      return current;
    },
    invalidate() {
      current += 1;
    },
    isCurrent(token) {
      return token === current;
    },
  });
}

export function mapSafeError(error) {
  const allowed = new Set([
    "support_forbidden",
    "support_mfa_required",
    "support_conflict",
    "support_invalid",
    "support_not_found",
    "support_guest_reply_unavailable",
    "support_unavailable",
    "support_admin_required",
    "account_not_found",
    "account_ambiguous",
    "no_deletion_pending",
  ]);
  const candidates = [error?.code, error?.message];
  for (const candidate of candidates) {
    if (typeof candidate !== "string") continue;
    for (const code of allowed) {
      if (candidate === code || candidate.includes(code)) return code;
    }
  }
  if (error?.status === 401 || error?.code === "PGRST301") return "session_expired";
  return "support_unavailable";
}

export function createSupportError(error) {
  return new SupportError(mapSafeError(error));
}
