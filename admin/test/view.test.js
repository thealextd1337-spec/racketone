import test from "node:test";
import assert from "node:assert/strict";
import { translate } from "../src/i18n.js";
import { node } from "../src/view.js";

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
