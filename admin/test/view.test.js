import test from "node:test";
import assert from "node:assert/strict";
import { translate } from "../src/i18n.js";
import { AdminView, node } from "../src/view.js";

test("localized UI copy retains German umlauts and English separator punctuation", () => {
  assert.match(translate("de", "inboxDescription"), /Featurew\u00fcnsche/);
  assert.equal(translate("de", "statusReviewing"), "In Pr\u00fcfung");
  assert.equal(translate("de", "saveChanges"), "\u00c4nderungen speichern");
  assert.equal(translate("de", "publicReply"), "\u00d6ffentliche Antwort");
  assert.equal(translate("en", "secureAccess"), "Private access \u00b7 two-factor verification required");
});

test("DOM helper rejects object text instead of rendering [object HTMLHeadingElement]", () => {
  assert.throws(() => node("div", "", { nodeType: 1 }), /Text content must be a primitive value/);
});

test("guest composer disables public replies and explains the in-app limitation in German", () => {
  class Element {
    constructor(tag) {
      this.tagName = tag;
      this.children = [];
      this.dataset = {};
      this.attributes = new Map();
    }
    append(...children) { this.children.push(...children); }
    setAttribute(name, value) { this.attributes.set(name, value); }
  }

  const previousDocument = globalThis.document;
  globalThis.document = {
    createElement: (tag) => new Element(tag),
    createElementNS: (_namespace, tag) => new Element(tag),
  };
  try {
    const view = Object.create(AdminView.prototype);
    const tree = view.renderComposer(
      { language: "de" },
      { id: "guest-ticket", owner_id: null },
      { composers: new Map(), pendingMessages: new Map() },
    );
    const descendants = (element) => [element, ...element.children.flatMap(descendants)];
    const elements = descendants(tree);
    const publicMode = elements.find((element) => element.tagName === "input" && element.value === "public");
    const publicHint = elements.find((element) => element.className === "composer-mode__hint" && element.textContent.startsWith("Gastmeldungen"));

    assert.equal(publicMode.disabled, true);
    assert.equal(publicHint.textContent, translate("de", "guestPublicReplyUnavailable"));
    assert.match(publicHint.textContent, /Interne Notizen/);
    assert.match(publicHint.textContent, /Kontakt-E-Mail/);

    for (const language of ["de", "en"]) {
      const errorTree = view.renderComposer(
        { language },
        { id: "guest-ticket", owner_id: null },
        {
          composers: new Map([[
            "guest-ticket",
            { body: "Guest reply draft", mode: "internal", error: "guestPublicReplyUnavailable" },
          ]]),
          pendingMessages: new Map(),
        },
      );
      const notice = descendants(errorTree).find((element) => element.className === "notice notice--error");
      const message = notice.children.find((element) => element.tagName === "span").textContent;
      assert.equal(message, translate(language, "guestPublicReplyUnavailable"));
      assert.notEqual(message, "support_guest_reply_unavailable");
    }

    const ownedTree = view.renderComposer(
      { language: "de" },
      { id: "owned-ticket", owner_id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee" },
      { composers: new Map(), pendingMessages: new Map() },
    );
    const ownedPublicMode = descendants(ownedTree).find((element) => element.tagName === "input" && element.value === "public");
    assert.equal(ownedPublicMode.disabled, false);
  } finally {
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
  }
});
