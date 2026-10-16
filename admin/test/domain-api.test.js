import test from "node:test";
import assert from "node:assert/strict";
import { createSupportApi } from "../src/api.js";
import { validatePublicKey, validateSupabaseConfig } from "../src/config.js";
import {
  buildTicketUpdateArgs,
  createRequestEpoch,
  makeMessageOperation,
  SupportError,
  validateMessage,
} from "../src/domain.js";

const ticketId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const memberId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

function jwtForRole(role) {
  const payload = Buffer.from(JSON.stringify({ role })).toString("base64url");
  return `header.${payload}.signature`;
}

test("Supabase config accepts only public keys and secure URLs", () => {
  const publicJwt = jwtForRole("anon");
  assert.equal(validatePublicKey(publicJwt), null);
  assert.equal(validatePublicKey(jwtForRole("service_role")), "secret-key");
  assert.equal(validatePublicKey("sb_secret_do_not_use"), "secret-key");
  assert.equal(validatePublicKey("sb_publishable_project_key"), null);
  assert.equal(validateSupabaseConfig("http://project.supabase.co", publicJwt).reason, "insecure-url");
  assert.equal(validateSupabaseConfig("http://127.0.0.1:54321", publicJwt).ok, true);
  assert.equal(validateSupabaseConfig("https://project.supabase.co", publicJwt).ok, true);
});

test("ticket update helper sends a complete snapshot and enforces bug resolution version", () => {
  const bug = { id: ticketId, category: "bug", version: 7 };
  assert.throws(() => buildTicketUpdateArgs(bug, {
    status: "resolved", priority: "high", assignee: "", relatedId: "", resolutionVersion: "",
  }), (error) => error instanceof SupportError && error.code === "resolution-version-required");
  assert.deepEqual(buildTicketUpdateArgs(bug, {
    status: "resolved", priority: "high", assignee: memberId, relatedId: "", resolutionVersion: " 2.3.1 ",
  }), {
    p_id: ticketId,
    p_status: "resolved",
    p_priority: "high",
    p_assignee: memberId,
    p_related_id: null,
    p_resolution_version: "2.3.1",
    p_expected_version: 7,
  });
  assert.throws(() => buildTicketUpdateArgs({ ...bug, version: 1 }, {
    status: "new", priority: "normal", assignee: "", relatedId: ticketId, resolutionVersion: "",
  }), (error) => error.code === "support_invalid");
});

test("message helper enforces the limit and freezes an immutable idempotency payload", () => {
  assert.equal(validateMessage("  "), "message-required");
  assert.equal(validateMessage("😀".repeat(4000)), null);
  assert.equal(validateMessage("😀".repeat(4001)), "message-too-long");
  let generated = 0;
  const operation = makeMessageOperation(ticketId, "  Please help  ", false, () => {
    generated += 1;
    return "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  });
  assert.equal(generated, 1);
  assert.equal(operation.args.p_body, "Please help");
  assert.equal(operation.args.p_internal, false);
  assert.equal(Object.isFrozen(operation), true);
  assert.equal(Object.isFrozen(operation.args), true);
  assert.strictEqual(operation.args, operation.args);
});

test("request epochs reject responses after a newer request or invalidation", () => {
  const epoch = createRequestEpoch();
  const first = epoch.next();
  const second = epoch.next();
  assert.equal(epoch.isCurrent(first), false);
  assert.equal(epoch.isCurrent(second), true);
  epoch.invalidate();
  assert.equal(epoch.isCurrent(second), false);
});

test("RPC adapter uses the settled names and argument shapes", async () => {
  const calls = [];
  const api = createSupportApi({
    async rpc(name, args) {
      calls.push({ name, args });
      return { data: name, error: null };
    },
  });
  await api.listTickets({ status: "new", search: "needle" });
  await api.getTicket(ticketId);
  await api.listMembers();
  await api.updateTicket({ p_id: ticketId, p_status: "new" });
  await api.sendMessage({ p_id: ticketId, p_internal: true });
  assert.deepEqual(calls, [
    { name: "admin_support_list", args: { p_status: "new", p_search: "needle" } },
    { name: "admin_support_ticket", args: { p_id: ticketId } },
    { name: "admin_support_members", args: {} },
    { name: "admin_support_update", args: { p_id: ticketId, p_status: "new" } },
    { name: "admin_support_message", args: { p_id: ticketId, p_internal: true } },
  ]);
});

test("RPC errors expose only mapped safe codes", async () => {
  const raw = new Error("private URL and token must stay hidden");
  raw.message = "private URL and token must stay hidden";
  const api = createSupportApi({ async rpc() { throw raw; } });
  await assert.rejects(api.listMembers(), (error) => {
    assert.equal(error.code, "support_unavailable");
    assert.equal(error.message, "support_unavailable");
    return true;
  });
  const conflict = createSupportApi({ async rpc() { return { error: { message: "support_conflict" } }; } });
  await assert.rejects(conflict.getTicket(ticketId), (error) => error.code === "support_conflict");
  const guestReply = createSupportApi({ async rpc() { return { error: { message: "support_guest_reply_unavailable" } }; } });
  await assert.rejects(guestReply.sendMessage({ p_id: ticketId, p_internal: false }), (error) => error.code === "support_guest_reply_unavailable");
});
