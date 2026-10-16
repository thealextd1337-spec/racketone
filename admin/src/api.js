import { createSupportError, SupportError } from "./domain.js";

function unwrapRpcResult(result) {
  if (result?.error) throw createSupportError(result.error);
  const data = result?.data;
  if (data && !Array.isArray(data) && typeof data.error === "string") {
    throw new SupportError(data.error);
  }
  return data;
}

export function createSupportApi(client) {
  async function rpc(name, args) {
    try {
      const result = await client.rpc(name, args);
      return unwrapRpcResult(result);
    } catch (error) {
      if (error instanceof SupportError) throw error;
      throw createSupportError(error);
    }
  }

  return Object.freeze({
    listTickets({ status = null, search = null } = {}) {
      return rpc("admin_support_list", { p_status: status, p_search: search });
    },
    getTicket(id) {
      return rpc("admin_support_ticket", { p_id: id });
    },
    listMembers() {
      return rpc("admin_support_members", {});
    },
    updateTicket(args) {
      return rpc("admin_support_update", args);
    },
    sendMessage(args) {
      return rpc("admin_support_message", args);
    },
  });
}