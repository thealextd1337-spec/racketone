import { createSupportError, SupportError } from "./domain.js";

function asSafeAuthError(error) {
  if (error instanceof SupportError) return error;
  const code = error?.code === "mfa_required" ? "support_mfa_required" : "auth_failed";
  return new SupportError(code);
}

function totpFactors(data) {
  return Array.isArray(data?.totp) ? data.totp : [];
}

function safeQrDataUrl(value) {
  return typeof value === "string"
    && value.length < 500000
    && /^data:image\/svg\+xml(?:;charset=utf-8|;utf-8|;base64)?,/i.test(value);
}

export function createAuthService(client) {
  async function assurance() {
    const { data, error } = await client.auth.mfa.getAuthenticatorAssuranceLevel();
    if (error) throw asSafeAuthError(error);
    return data?.currentLevel === "aal2" ? "aal2" : "aal1";
  }

  async function factors() {
    const { data, error } = await client.auth.mfa.listFactors();
    if (error) throw asSafeAuthError(error);
    return totpFactors(data);
  }

  async function signIn(emailValue, password) {
    try {
      const email = String(emailValue ?? "").trim();
      const { data, error } = await client.auth.signInWithPassword({ email, password });
      if (error) throw asSafeAuthError(error);
      if (!data?.session || !data?.user) throw new SupportError("auth_failed");
      const currentAssurance = await assurance();
      const available = await factors();
      const verified = available.find((factor) => factor.status === "verified");

      if (verified && currentAssurance === "aal2") {
        return { kind: "unlocked", email: data.user.email ?? email, aal: "aal2" };
      }
      if (verified) {
        return { kind: "challenge", email: data.user.email ?? email, factorId: verified.id };
      }

      const pending = available.filter((factor) => factor.status !== "verified");
      for (const factor of pending) {
        const { error: removeError } = await client.auth.mfa.unenroll({ factorId: factor.id });
        if (removeError) throw asSafeAuthError(removeError);
      }
      const { data: enrolled, error: enrollError } = await client.auth.mfa.enroll({
        factorType: "totp",
        friendlyName: "Advantly support",
      });
      if (enrollError) throw asSafeAuthError(enrollError);
      if (!enrolled?.id || !safeQrDataUrl(enrolled?.totp?.qr_code) || !enrolled?.totp?.secret) {
        throw new SupportError("auth_failed");
      }
      return {
        kind: "setup",
        email: data.user.email ?? email,
        factorId: enrolled.id,
        qrCode: enrolled.totp.qr_code,
        secret: enrolled.totp.secret,
      };
    } catch (error) {
      throw asSafeAuthError(error);
    }
  }

  async function verifyTotp(factorId, codeValue) {
    try {
      const code = String(codeValue ?? "").replace(/\s/g, "");
      if (!factorId || !/^\d{6,8}$/.test(code)) throw new SupportError("invalid-totp");
      const { error } = await client.auth.mfa.challengeAndVerify({ factorId, code });
      if (error) throw asSafeAuthError(error);
      const [currentAssurance, available] = await Promise.all([assurance(), factors()]);
      if (currentAssurance !== "aal2" || !available.some((factor) => factor.id === factorId && factor.status === "verified")) {
        throw new SupportError("support_mfa_required");
      }
      return { aal: "aal2" };
    } catch (error) {
      throw asSafeAuthError(error);
    }
  }

  async function signOut() {
    try {
      const { error } = await client.auth.signOut();
      if (error) throw createSupportError(error);
    } catch {
      // Der Aufrufer leert die Oberfläche auch bei fehlgeschlagener Server-Abmeldung.
    }
  }

  function onAuthStateChange(callback) {
    const { data } = client.auth.onAuthStateChange((event, session) => {
      if (event === "SIGNED_OUT" || (event === "TOKEN_REFRESHED" && !session)) callback(event);
    });
    return () => data?.subscription?.unsubscribe?.();
  }

  return Object.freeze({ signIn, verifyTotp, signOut, onAuthStateChange });
}
