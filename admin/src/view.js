import { categoryLabel, priorityLabel, statusLabel, translate } from "./i18n.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const iconParts = {
  brand: [["path", "M5 20 12 5l7 15h-4l-3-6-3 6H5Z"], ["path", "M9.5 16h5"]],
  lock: [["rect", "M5 10 14 10"], ["path", "M8 10V7a4 4 0 0 1 8 0v3"], ["path", "M12 14v2"]],
  shield: [["path", "M12 3 20 6v5c0 5-3.4 8.6-8 10-4.6-1.4-8-5-8-10V6l8-3Z"], ["path", "m9 12 2 2 4-4"]],
  search: [["circle", "10.8|10.8|6.8"], ["path", "m16 16 4.5 4.5"]],
  refresh: [["path", "M20 7v5h-5"], ["path", "M4.8 9a7.5 7.5 0 0 1 12.6-2L20 12"], ["path", "M4 17v-5h5"], ["path", "M19.2 15a7.5 7.5 0 0 1-12.6 2L4 12"]],
  logout: [["path", "m10 17 5-5-5-5"], ["path", "M15 12H3"], ["path", "M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6"]],
  back: [["path", "m15 18-6-6 6-6"], ["path", "M9 12h12"]],
  inbox: [["path", "M4 5h16v14H4z"], ["path", "M4 14h4l2 2h4l2-2h4"]],
  warning: [["path", "M12 3 22 20H2L12 3Z"], ["path", "M12 9v5"], ["circle", "12|17|0.5"]],
  arrow: [["path", "M5 12h14"], ["path", "m13 6 6 6-6 6"]],
};

export function node(tag, className = "", text = null) {
  if (text !== null && text !== undefined && typeof text === "object") {
    throw new TypeError("Text content must be a primitive value");
  }
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== null && text !== undefined) element.textContent = String(text);
  return element;
}

function icon(name, className = "icon") {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("class", className);
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  for (const [tag, data] of iconParts[name] ?? []) {
    const shape = document.createElementNS(SVG_NS, tag);
    if (tag === "circle") {
      const [cx, cy, radius] = data.split("|");
      shape.setAttribute("cx", cx);
      shape.setAttribute("cy", cy);
      shape.setAttribute("r", radius);
    } else if (tag === "rect") {
      shape.setAttribute("x", "5"); shape.setAttribute("y", "10");
      shape.setAttribute("width", "14"); shape.setAttribute("height", "10");
    } else shape.setAttribute("d", data);
    shape.setAttribute("fill", "none");
    shape.setAttribute("stroke", "currentColor");
    shape.setAttribute("stroke-width", "1.8");
    shape.setAttribute("stroke-linecap", "round");
    shape.setAttribute("stroke-linejoin", "round");
    svg.append(shape);
  }
  return svg;
}

function setFocusKey(element, key) {
  element.dataset.focusKey = key;
  return element;
}

function makeButton(label, action, className = "button button--quiet", iconName = null) {
  const button = node("button", className);
  button.type = "button";
  button.dataset.action = action;
  if (iconName) button.append(icon(iconName));
  button.append(node("span", "", label));
  return button;
}

function addFieldLabel(labelText, input, className = "field") {
  const wrapper = node("label", className);
  wrapper.append(node("span", "field__label", labelText), input);
  return wrapper;
}

function fieldInput({ type = "text", value = "", placeholder = "", name, focusKey, autocomplete, required = false, maxLength }) {
  const input = node("input", "control");
  input.type = type;
  input.name = name;
  input.value = value ?? "";
  input.placeholder = placeholder;
  input.autocomplete = autocomplete ?? "off";
  input.required = required;
  if (maxLength) input.maxLength = maxLength;
  if (focusKey) setFocusKey(input, focusKey);
  return input;
}

function selectControl(name, options, selected) {
  const select = node("select", "control");
  select.name = name;
  for (const data of options) {
    const option = node("option", "", data.label);
    option.value = data.value;
    option.selected = data.value === selected;
    select.append(option);
  }
  return select;
}

function locale(language) { return language === "de" ? "de-DE" : "en-GB"; }
function shortId(value) { return typeof value === "string" ? value.slice(0, 8) : ""; }

function formatDate(value, language) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString(locale(language), { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

function relativeAge(value, language) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const elapsed = Math.round((date.getTime() - Date.now()) / 1000);
  if (Math.abs(elapsed) < 60) return translate(language, "ageNow");
  const units = [[31536000, "year"], [2592000, "month"], [86400, "day"], [3600, "hour"], [60, "minute"]];
  for (const [seconds, unit] of units) {
    if (Math.abs(elapsed) >= seconds) return new Intl.RelativeTimeFormat(locale(language), { numeric: "auto" }).format(Math.round(elapsed / seconds), unit);
  }
  return translate(language, "ageNow");
}

function statusClass(status) {
  return `status status--${["new", "reviewing", "planned", "resolved", "closed"].includes(status) ? status : "new"}`;
}
function categoryClass(category) {
  return `category category--${["feedback", "feature", "bug"].includes(category) ? category : "feedback"}`;
}
function statusOptions(language) {
  return ["new", "reviewing", "planned", "resolved", "closed"].map((value) => ({ value, label: statusLabel(language, value) }));
}
function priorityOptions(language) {
  return ["low", "normal", "high"].map((value) => ({ value, label: priorityLabel(language, value) }));
}
function alertBox(language, key, style = "error") {
  const box = node("div", `notice notice--${style}`);
  box.setAttribute("role", style === "error" ? "alert" : "status");
  if (style === "error") box.append(icon("warning", "icon notice__icon"));
  box.append(node("span", "", translate(language, key)));
  return box;
}

export class AdminView {
  constructor({ app = document.getElementById("app"), dialog = document.getElementById("public-confirm") } = {}) {
    this.app = app;
    this.dialog = dialog;
    this.listeners = new Map();
    this.handleClick = (event) => {
      const target = event.target.closest?.("[data-action]");
      if (!target) return;
      if (target.dataset.action === "language") this.emit("language", { language: target.dataset.language });
      else if (target.dataset.action === "select-ticket") this.emit("select-ticket", { id: target.dataset.id });
      else this.emit(target.dataset.action);
    };
    this.handleSubmit = (event) => {
      const form = event.target;
      if (!(form instanceof HTMLFormElement) || !form.dataset.form) return;
      event.preventDefault();
      const values = new FormData(form);
      if (form.dataset.form === "sign-in") {
        const passwordInput = form.elements.namedItem("password");
        const password = passwordInput?.value ?? "";
        if (passwordInput) passwordInput.value = "";
        this.emit("sign-in", { email: values.get("email"), password });
      } else if (form.dataset.form === "verify-totp") this.emit("verify-totp", { code: values.get("code") });
      else if (form.dataset.form === "ticket-update") this.emit("save-ticket", { fields: Object.fromEntries(values.entries()) });
      else if (form.dataset.form === "message") this.emit("send-message");
    };
    this.handleInput = (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;
      if (target.dataset.field === "search") this.emit("search", { value: target.value });
      else if (target.dataset.field === "message") this.emit("composer", { body: target.value, mode: this.getComposerMode() });
      else if (target.dataset.ticketField) this.emit("ticket-draft", { fields: { [target.dataset.ticketField]: target.value } });
    };
    this.handleChange = (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;
      if (target.dataset.field === "status-filter") this.emit("status-filter", { value: target.value });
      else if (target.dataset.composerMode) this.emit("composer", { body: this.app.querySelector('[data-field="message"]')?.value ?? "", mode: target.value });
      else if (target.dataset.ticketField) this.emit("ticket-draft", { fields: { [target.dataset.ticketField]: target.value } });
    };
    document.addEventListener("click", this.handleClick);
    document.addEventListener("submit", this.handleSubmit);
    document.addEventListener("input", this.handleInput);
    document.addEventListener("change", this.handleChange);
    this.dialog?.addEventListener("cancel", () => this.emit("cancel-public-send"));
  }

  on(event, callback) {
    if (!this.listeners.has(event)) this.listeners.set(event, new Set());
    this.listeners.get(event).add(callback);
  }
  off(event, callback) { this.listeners.get(event)?.delete(callback); }
  emit(event, payload = {}) { for (const callback of this.listeners.get(event) ?? []) callback(payload); }
  getComposerMode() { return this.app.querySelector('[data-composer-mode]:checked')?.value ?? "internal"; }

  render(state, data = {}) {
    this.currentLanguage = state.language;
    document.documentElement.lang = state.language;
    this.currentLanguage = state.language;
    const active = document.activeElement;
    const focusKey = active && this.app.contains(active) ? active.dataset.focusKey : null;
    const start = active && typeof active.selectionStart === "number" ? active.selectionStart : null;
    const end = active && typeof active.selectionEnd === "number" ? active.selectionEnd : null;
    const frame = node("div", "app-frame");
    if (state.phase === "missing-config") frame.append(this.renderMissingConfig(state));
    else if (state.phase === "login") frame.append(this.renderLogin(state));
    else if (state.phase === "mfa-setup" || state.phase === "mfa-challenge") frame.append(this.renderMfa(state));
    else frame.append(this.renderInbox(state, data));
    this.app.replaceChildren(frame);
    if (focusKey) {
      const next = this.app.querySelector(`[data-focus-key="${focusKey}"]`);
      if (next) {
        next.focus({ preventScroll: true });
        if (start !== null && typeof next.setSelectionRange === "function") {
          try { next.setSelectionRange(start, end); } catch { /* Nicht jeder Eingabetyp unterstützt Textauswahl. */ }
        }
      }
    }
  }

  renderTopbar(language, { signedIn = false, email = "" } = {}) {
    const header = node("header", "topbar");
    const inner = node("div", "topbar__inner");
    const brand = node("div", "brand-lockup");
    const mark = node("span", "brand-mark");
    mark.append(icon("brand", "brand-mark__icon"));
    brand.append(mark, node("span", "brand-lockup__name", translate(language, "brand")));
    const controls = node("div", "topbar__controls");
    controls.append(this.languageToggle(language));
    if (signedIn) {
      const identity = node("span", "account-email", email);
      identity.title = email;
      controls.append(identity, makeButton(translate(language, "signOut"), "sign-out", "button button--quiet button--small", "logout"));
    }
    inner.append(brand, controls);
    header.append(inner);
    return header;
  }

  languageToggle(language) {
    const group = node("div", "language-toggle");
    group.setAttribute("role", "group");
    group.setAttribute("aria-label", translate(language, "switchLanguage"));
    for (const [value, label] of [["en", "EN"], ["de", "DE"]]) {
      const button = node("button", `language-toggle__button${language === value ? " is-active" : ""}`, label);
      button.type = "button"; button.dataset.action = "language"; button.dataset.language = value;
      button.setAttribute("aria-pressed", String(language === value));
      group.append(button);
    }
    return group;
  }

  authScaffold(state, content) {
    const page = node("div", "auth-page");
    page.append(this.renderTopbar(state.language));
    const grid = node("div", "auth-layout");
    const aside = node("aside", "auth-aside");
    const access = node("div", "access-mark");
    access.append(icon("shield", "icon"), node("span", "", translate(state.language, "secureAccess")));
    aside.append(access, node("h2", "auth-aside__heading", translate(state.language, "asideHeading")), node("p", "auth-aside__copy", translate(state.language, "asideCopy")));
    const checks = node("ul", "access-checks");
    for (const key of ["accessCheckAccount", "accessCheckTotp", "accessCheckRole"]) {
      const item = node("li", ""); item.append(icon("lock", "icon"), node("span", "", translate(state.language, key))); checks.append(item);
    }
    aside.append(checks);
    grid.append(aside, content);
    page.append(grid, this.renderFooter(state.language));
    return page;
  }

  renderFooter(language) {
    const footer = node("footer", "auth-footer");
    footer.append(node("span", "", translate(language, "privateInbox")), node("span", "auth-footer__separator", "·"), node("span", "", translate(language, "footerNote")));
    return footer;
  }

  renderMissingConfig(state) {
    const card = node("section", "auth-card config-card");
    card.append(icon("warning", "icon config-card__icon"), node("h1", "auth-card__heading", translate(state.language, "configHeading")), node("p", "auth-card__copy", translate(state.language, "configCopy")));
    const key = { "missing-url": "configMissingUrl", "missing-key": "configMissingKey", "secret-key": "configSecretKey", "insecure-url": "configInsecureUrl", "invalid-url": "configInvalid", "invalid-key": "configInvalid" }[state.configReason] ?? "configInvalid";
    card.append(alertBox(state.language, key), node("p", "config-card__help", translate(state.language, "configHelp")));
    return this.authScaffold(state, card);
  }

  renderLogin(state) {
    const card = node("section", "auth-card");
    card.append(node("h1", "auth-card__heading", translate(state.language, "signInHeading")), node("p", "auth-card__copy", translate(state.language, "signInCopy")));
    if (state.authError) card.append(alertBox(state.language, state.authError));
    const form = node("form", "auth-form"); form.dataset.form = "sign-in";
    const email = fieldInput({ type: "email", value: state.loginEmail, placeholder: translate(state.language, "emailPlaceholder"), name: "email", focusKey: "login-email", autocomplete: "username", required: true });
    email.inputMode = "email"; email.autocapitalize = "none"; email.spellcheck = false;
    const password = fieldInput({ type: "password", name: "password", focusKey: "login-password", autocomplete: "current-password", required: true });
    form.append(addFieldLabel(translate(state.language, "email"), email), addFieldLabel(translate(state.language, "password"), password));
    const submit = node("button", "button button--accent button--wide", state.busy ? translate(state.language, "signingIn") : translate(state.language, "signIn"));
    submit.type = "submit"; submit.disabled = state.busy; submit.append(icon("arrow", "icon button__trailing-icon")); form.append(submit);
    card.append(form);
    return this.authScaffold(state, card);
  }

  renderMfa(state) {
    const setupMode = state.phase === "mfa-setup";
    const card = node("section", "auth-card mfa-card");
    card.append(node("h1", "auth-card__heading", translate(state.language, setupMode ? "mfaSetupHeading" : "mfaChallengeHeading")), node("p", "auth-card__copy", translate(state.language, setupMode ? "mfaSetupCopy" : "mfaChallengeCopy")));
    if (state.authError) card.append(alertBox(state.language, state.authError));
    if (setupMode) {
      const setup = node("div", "totp-setup");
      const image = node("img", "totp-setup__qr"); image.src = state.qrCode; image.alt = translate(state.language, "qrAlt"); image.referrerPolicy = "no-referrer";
      const manual = node("div", "totp-setup__manual");
      manual.append(node("p", "field__label", translate(state.language, "manualSecret")), node("code", "totp-setup__secret", state.secret));
      setup.append(image, manual); card.append(setup);
    }
    const form = node("form", "auth-form mfa-form"); form.dataset.form = "verify-totp";
    const code = fieldInput({ type: "text", placeholder: translate(state.language, "codePlaceholder"), name: "code", focusKey: "totp-code", autocomplete: "one-time-code", required: true, maxLength: 8 });
    code.inputMode = "numeric"; code.pattern = "[0-9 ]{6,9}"; code.autocapitalize = "off";
    form.append(addFieldLabel(translate(state.language, "authenticatorCode"), code));
    const submit = node("button", "button button--accent button--wide", state.busy ? translate(state.language, "verifying") : translate(state.language, "verifyAndOpen"));
    submit.type = "submit"; submit.disabled = state.busy; submit.append(icon("arrow", "icon button__trailing-icon")); form.append(submit);
    card.append(form, makeButton(translate(state.language, "backToSignIn"), "sign-out", "button button--link"));
    return this.authScaffold(state, card);
  }
  renderInbox(state, data) {
    const page = node("div", "inbox-page");
    page.append(this.renderTopbar(state.language, { signedIn: true, email: state.email }));
    const shell = node("div", "inbox-shell");
    const heading = node("div", "inbox-heading");
    const titleGroup = node("div", "");
    titleGroup.append(node("h1", "inbox-heading__title", translate(state.language, "inboxTitle")), node("p", "inbox-heading__copy", translate(state.language, "inboxDescription")));
    const refresh = makeButton(state.listLoading ? translate(state.language, "refreshing") : translate(state.language, "refresh"), "refresh", "button button--quiet refresh-button", "refresh");
    refresh.disabled = state.listLoading;
    refresh.setAttribute("aria-label", translate(state.language, "refresh"));
    refresh.setAttribute("aria-busy", String(state.listLoading));
    heading.append(titleGroup, refresh);

    const workspace = node("div", `workspace${state.selectedId ? " has-selection" : ""}`);
    workspace.append(this.renderList(state), this.renderDetail(state, data));
    shell.append(heading, workspace);
    page.append(shell);
    return page;
  }

  renderList(state) {
    const panel = node("aside", "inbox-panel");
    const panelHeading = node("div", "inbox-panel__heading");
    const titleRow = node("div", "inbox-panel__title-row");
    titleRow.append(node("h2", "section-title", translate(state.language, "tickets")), node("span", "count-label", String(state.tickets.length)));
    panelHeading.append(titleRow);

    const searchWrap = node("label", "search-field");
    searchWrap.append(icon("search", "icon search-field__icon"));
    const search = fieldInput({ type: "search", value: state.search, placeholder: translate(state.language, "searchPlaceholder"), name: "search", focusKey: "ticket-search", autocomplete: "off", maxLength: 120 });
    search.dataset.field = "search";
    search.setAttribute("aria-label", translate(state.language, "searchPlaceholder"));
    searchWrap.append(search);
    const filter = selectControl("status", [{ value: "", label: translate(state.language, "allStatuses") }, ...statusOptions(state.language)], state.statusFilter ?? "");
    filter.dataset.field = "status-filter";
    filter.setAttribute("aria-label", translate(state.language, "statusFilter"));
    panelHeading.append(searchWrap, filter);

    const list = node("div", "ticket-list");
    list.setAttribute("aria-label", translate(state.language, "tickets"));
    if (state.listLoading && state.tickets.length === 0) {
      const loading = node("div", "ticket-skeleton-list");
      for (let index = 0; index < 5; index += 1) {
        const row = node("div", "ticket-skeleton");
        row.setAttribute("aria-hidden", "true");
        row.append(node("span", "skeleton-line skeleton-line--short"), node("span", "skeleton-line"), node("span", "skeleton-line skeleton-line--medium"));
        loading.append(row);
      }
      loading.append(node("p", "sr-only", translate(state.language, "loadingTickets")));
      list.append(loading);
    } else if (state.listError) {
      list.append(this.emptyState("inbox-empty inbox-empty--error", "listError", "", "retry", state.language));
    } else if (state.tickets.length === 0) {
      const filtered = Boolean(state.search.trim() || state.statusFilter);
      list.append(this.emptyState("inbox-empty", filtered ? "noResultsTitle" : "emptyInboxTitle", filtered ? "noResultsCopy" : "emptyInboxCopy", null, state.language));
    } else {
      for (const ticket of state.tickets) list.append(this.ticketRow(state, ticket));
    }
    panel.append(panelHeading, list);
    return panel;
  }

  emptyState(className, headingKey, copyKey, action, language) {
    const empty = node("div", className);
    empty.append(icon(action ? "warning" : "inbox", "icon inbox-empty__icon"), node("h3", "inbox-empty__title", translate(language, headingKey)));
    if (copyKey) empty.append(node("p", "inbox-empty__copy", translate(language, copyKey)));
    if (action) empty.append(makeButton(translate(language, "retry"), action, "button button--quiet button--small", "refresh"));
    return empty;
  }

  ticketRow(state, ticket) {
    const button = node("button", `ticket-row${ticket.id === state.selectedId ? " is-selected" : ""}`);
    button.type = "button";
    button.dataset.action = "select-ticket";
    button.dataset.id = ticket.id;
    if (ticket.id === state.selectedId) button.setAttribute("aria-current", "true");
    const top = node("div", "ticket-row__top");
    const category = node("span", categoryClass(ticket.category), categoryLabel(state.language, ticket.category));
    const age = node("time", "ticket-row__age", relativeAge(ticket.created_at, state.language));
    age.dateTime = ticket.created_at ?? "";
    age.title = formatDate(ticket.created_at, state.language);
    top.append(category, age);
    const title = node("span", "ticket-row__title", ticket.title ?? "");
    const bottom = node("div", "ticket-row__bottom");
    bottom.append(node("span", statusClass(ticket.status), statusLabel(state.language, ticket.status)), node("span", "ticket-row__id", "#" + shortId(ticket.id)));
    if (ticket.priority === "high") bottom.append(node("span", "priority-marker", priorityLabel(state.language, "high")));
    button.append(top, title, bottom);
    return button;
  }

  renderDetail(state, data) {
    const panel = node("section", "detail-panel");
    if (!state.selectedId) {
      panel.append(this.emptyState("detail-empty", "detailEmptyTitle", "detailEmptyCopy", null, state.language));
      return panel;
    }
    if (state.detailLoading && !state.ticket) {
      const loading = node("div", "detail-loading");
      loading.append(node("p", "field__label", translate(state.language, "loadingTicket")), node("span", "skeleton-line skeleton-line--medium"), node("span", "skeleton-line skeleton-line--long"), node("span", "skeleton-line skeleton-line--long"));
      panel.append(loading);
      return panel;
    }
    if (!state.ticket) {
      panel.append(this.emptyState("detail-empty detail-empty--error", state.detailError ?? "genericError", "", "retry-ticket", state.language));
      return panel;
    }
    const ticket = state.ticket;
    const detail = node("div", "ticket-detail");
    detail.append(this.renderDetailHeader(state, ticket), this.renderTicketBody(state, ticket), this.renderMetadata(state, ticket), this.renderDiagnostics(state, ticket), this.renderUpdateForm(state, ticket, data.members ?? []), this.renderConversation(state, ticket), this.renderComposer(state, ticket, data));
    panel.append(detail);
    return panel;
  }

  renderDetailHeader(state, ticket) {
    const header = node("div", "detail-header");
    const eyebrow = node("div", "detail-header__eyebrow");
    eyebrow.append(makeButton(translate(state.language, "backToTickets"), "back-to-list", "button button--quiet button--small detail-back", "back"));
    eyebrow.append(node("span", categoryClass(ticket.category), categoryLabel(state.language, ticket.category)), node("span", "ticket-id", "#" + ticket.id));
    const title = node("h2", "detail-title", ticket.title ?? "");
    const meta = node("div", "detail-header__meta");
    const priorityTone = ticket.priority === "high" ? "high" : ticket.priority === "low" ? "low" : "normal";
    meta.append(node("span", statusClass(ticket.status), statusLabel(state.language, ticket.status)), node("span", `priority-label priority-label--${priorityTone}`, priorityLabel(state.language, ticket.priority)));
    header.append(eyebrow, title, meta);
    return header;
  }

  renderTicketBody(state, ticket) {
    const section = node("section", "ticket-description");
    section.setAttribute("aria-label", translate(state.language, "details"));
    section.append(node("p", "ticket-description__body", ticket.body ?? ""));
    return section;
  }

  renderMetadata(state, ticket) {
    const section = node("section", "metadata-strip");
    const received = node("div", "metadata-item");
    received.append(node("span", "metadata-item__label", translate(state.language, "created")), node("time", "metadata-item__value", formatDate(ticket.created_at, state.language)));
    const updated = node("div", "metadata-item");
    updated.append(node("span", "metadata-item__label", translate(state.language, "updated")), node("time", "metadata-item__value", formatDate(ticket.updated_at, state.language)));
    section.append(received, updated);
    if (ticket.contact_email) {
      const contact = node("div", "metadata-item");
      contact.append(node("span", "metadata-item__label", translate(state.language, "contact")), node("span", "metadata-item__value metadata-item__value--contact", ticket.contact_email));
      section.append(contact);
    }
    return section;
  }

  renderDiagnostics(state, ticket) {
    const details = node("details", "diagnostic-details");
    const summary = node("summary", "diagnostic-details__summary");
    const diagnostics = ticket.diagnostics && typeof ticket.diagnostics === "object" ? ticket.diagnostics : {};
    summary.append(node("span", "section-title", translate(state.language, "diagnostics")), node("span", "diagnostic-details__count", String(Object.keys(diagnostics).length)));
    details.append(summary);
    const body = node("div", "diagnostic-list");
    const entries = Object.entries(diagnostics).slice(0, 40);
    if (entries.length === 0) body.append(node("p", "diagnostics-empty", translate(state.language, "diagnosticsEmpty")));
    for (const [key, value] of entries) {
      let text;
      if (typeof value === "string") text = value;
      else {
        try { text = JSON.stringify(value); } catch { text = String(value); }
      }
      if (text.length > 600) text = text.slice(0, 597) + "…";
      const row = node("div", "diagnostic-row");
      row.append(node("span", "diagnostic-row__key", String(key).slice(0, 100)), node("code", "diagnostic-row__value", text));
      body.append(row);
    }
    details.append(body);
    return details;
  }
  renderUpdateForm(state, ticket, members) {
    const section = node("section", "update-section");
    const heading = node("div", "subsection-heading");
    heading.append(node("h3", "section-title", translate(state.language, "details")));
    section.append(heading);
    const form = node("form", "update-form");
    form.dataset.form = "ticket-update";
    const draft = state.updateDraft ?? {};
    const status = selectControl("status", statusOptions(state.language), draft.status);
    status.dataset.ticketField = "status";
    const priority = selectControl("priority", priorityOptions(state.language), draft.priority);
    priority.dataset.ticketField = "priority";
    const memberOptions = [{ value: "", label: translate(state.language, "unassigned") }];
    for (const member of members) {
      const role = member.role === "admin" ? "memberAdmin" : "memberSupport";
      memberOptions.push({ value: member.id, label: translate(state.language, role) + " · " + shortId(member.id) });
    }
    const assignee = selectControl("assignee", memberOptions, draft.assignee ?? "");
    assignee.dataset.ticketField = "assignee";
    const related = fieldInput({ value: draft.relatedId, placeholder: translate(state.language, "relatedPlaceholder"), name: "relatedId", focusKey: "related-ticket" });
    related.dataset.ticketField = "relatedId"; related.autocapitalize = "none"; related.spellcheck = false;
    const resolution = fieldInput({ value: draft.resolutionVersion, placeholder: translate(state.language, "versionPlaceholder"), name: "resolutionVersion", focusKey: "resolution-version", maxLength: 80 });
    resolution.dataset.ticketField = "resolutionVersion";
    const grid = node("div", "update-form__grid");
    grid.append(
      addFieldLabel(translate(state.language, "status"), status),
      addFieldLabel(translate(state.language, "priority"), priority),
      addFieldLabel(translate(state.language, "assignee"), assignee),
      addFieldLabel(translate(state.language, "relatedTicket"), related),
      addFieldLabel(translate(state.language, "resolutionVersion"), resolution),
    );
    form.append(grid);
    if (ticket.category === "bug" && draft.status === "resolved" && !String(draft.resolutionVersion ?? "").trim()) {
      form.append(node("p", "field-hint field-hint--warning", translate(state.language, "bugResolutionRequired")));
    }
    const actions = node("div", "form-actions");
    if (state.detailNotice) {
      const style = state.detailNotice === "saved" ? "success" : state.detailNotice === "conflict" ? "warning" : "error";
      actions.append(alertBox(state.language, state.detailNotice, style));
    }
    const submit = node("button", "button button--accent", state.saving ? translate(state.language, "saving") : translate(state.language, "saveChanges"));
    submit.type = "submit"; submit.disabled = state.saving;
    actions.append(submit);
    form.append(actions);
    section.append(form);
    return section;
  }

  renderConversation(state, ticket) {
    const section = node("section", "conversation-section");
    const heading = node("div", "subsection-heading");
    const messages = Array.isArray(ticket.messages) ? ticket.messages : [];
    heading.append(node("h3", "section-title", translate(state.language, "conversation")), node("span", "count-label", String(messages.length)));
    section.append(heading);
    if (messages.length === 0) {
      section.append(node("p", "conversation-empty", translate(state.language, "noMessages")));
      return section;
    }
    const thread = node("ol", "thread");
    for (const message of messages) {
      const entry = node("li", `thread-entry${message.internal ? " thread-entry--internal" : ""}`);
      const head = node("div", "thread-entry__head");
      const kindKey = message.internal ? "internalResponseLabel" : message.author_id ? "publicResponseLabel" : "requester";
      head.append(node("span", message.internal ? "thread-kind thread-kind--internal" : "thread-kind thread-kind--public", translate(state.language, kindKey)));
      const date = node("time", "thread-entry__date", formatDate(message.created_at, state.language));
      date.dateTime = message.created_at ?? "";
      head.append(date);
      entry.append(head, node("p", "thread-entry__body", message.body ?? ""));
      thread.append(entry);
    }
    section.append(thread);
    return section;
  }

  renderComposer(state, ticket, data) {
    const section = node("section", "composer-section");
    section.append(node("h3", "section-title", translate(state.language, "messageLabel")));
    const pending = data.pendingMessages.get(ticket.id);
    const draft = data.composers.get(ticket.id) ?? { body: "", mode: "internal", error: null };
    const form = node("form", "composer-form");
    form.dataset.form = "message";
    const guestTicket = ticket.owner_id === null || ticket.owner_id === undefined;

    if (pending) {
      const modeKey = pending.operation.internal ? "internalResponseLabel" : "publicResponseLabel";
      form.append(node("div", `pending-message__mode${pending.operation.internal ? " is-internal" : " is-public"}`, translate(state.language, modeKey)));
      const textarea = node("textarea", "control composer-textarea composer-textarea--locked", pending.operation.body);
      textarea.disabled = true; textarea.rows = 5; textarea.maxLength = 4000;
      form.append(textarea);
      if (pending.error) form.append(alertBox(state.language, pending.error));
      const buttons = node("div", "composer-actions");
      if (pending.status === "sending") {
        const sending = node("span", "composer-status", translate(state.language, "sending"));
        sending.append(node("span", "loading-dot")); buttons.append(sending);
      } else {
        buttons.append(makeButton(translate(state.language, "retrySend"), "retry-message", "button button--accent"), makeButton(translate(state.language, "discardDraft"), "discard-message", "button button--quiet"));
      }
      form.append(buttons); section.append(form); return section;
    }

    const modes = node("fieldset", "composer-modes");
    modes.append(node("legend", "field__label", translate(state.language, "messageLabel")));
    for (const mode of ["internal", "public"]) {
      const input = node("input", "");
      input.type = "radio"; input.name = "messageMode"; input.value = mode; input.checked = draft.mode === mode; input.dataset.composerMode = "true";
      input.disabled = mode === "public" && guestTicket;
      const label = node("label", `composer-mode composer-mode--${mode}`);
      label.append(input, node("span", "composer-mode__title", translate(state.language, mode === "internal" ? "internalNote" : "publicReply")));
      const hintKey = mode === "internal" ? "internalHint" : guestTicket ? "guestPublicReplyUnavailable" : "publicHint";
      label.append(node("span", "composer-mode__hint", translate(state.language, hintKey)));
      modes.append(label);
    }
    form.append(modes);
    const textarea = node("textarea", "control composer-textarea", draft.body);
    textarea.name = "message"; textarea.rows = 5; textarea.maxLength = 4000;
    textarea.placeholder = translate(state.language, "messagePlaceholder");
    textarea.dataset.field = "message"; setFocusKey(textarea, "message-composer");
    textarea.setAttribute("aria-label", translate(state.language, "messageLabel"));
    form.append(textarea);
    if (draft.error) form.append(alertBox(state.language, draft.error));
    const actions = node("div", "composer-actions");
    const submit = node("button", `button ${draft.mode === "internal" ? "button--quiet" : "button--accent"}`, translate(state.language, draft.mode === "internal" ? "sendNote" : "sendReply"));
    submit.type = "submit"; actions.append(submit); form.append(actions);
    section.append(form);
    return section;
  }

  showPublicConfirmation(messageText) {
    const language = this.currentLanguage ?? "en";
    const heading = this.dialog?.querySelector("#public-confirm-title");
    const copy = this.dialog?.querySelector("#public-confirm-copy");
    const preview = this.dialog?.querySelector("#public-confirm-preview");
    const cancel = this.dialog?.querySelector("#public-cancel");
    const accept = this.dialog?.querySelector("#public-accept");
    if (heading) heading.textContent = translate(language, "publicConfirmTitle");
    if (copy) copy.textContent = translate(language, "publicConfirmCopy");
    if (preview) preview.textContent = messageText ?? "";
    if (cancel) cancel.textContent = translate(language, "cancel");
    if (accept) accept.textContent = translate(language, "confirmSend");
    if (this.dialog && !this.dialog.open) this.dialog.showModal();
  }

  hidePublicConfirmation() {
    if (this.dialog?.open) this.dialog.close();
  }

  dispose() {
    document.removeEventListener("click", this.handleClick);
    document.removeEventListener("submit", this.handleSubmit);
    document.removeEventListener("input", this.handleInput);
    document.removeEventListener("change", this.handleChange);
    this.listeners.clear();
  }
}
